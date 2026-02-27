package com.example.livetranscription.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.net.http.WebSocket.Listener;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Adapter that connects to AssemblyAI realtime WebSocket and proxies messages.
 * Tries "Bearer <key>" first, and if rejected with 4001 Not authorized will
 * retry once
 * using the raw API key in the Authorization header.
 */
public class AssemblyAITranscriptionService implements TranscriptionService {
    private static final Logger log = LoggerFactory.getLogger(AssemblyAITranscriptionService.class);

    private volatile WebSocket upstream;
    private final WebSocketSession clientSession;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final HttpClient httpClient;
    private final String apiKey;
    private final int sampleRate;
    private final String wsUrl;
    private final int minSilenceThreshold;
    private final int maxSilenceThreshold;
    private final double endOfTurnThreshold;

    public AssemblyAITranscriptionService(WebSocketSession clientSession, String apiKey, int sampleRate, String wsUrl,
            int minSilenceThreshold, int maxSilenceThreshold, double endOfTurnThreshold) {
        this.clientSession = clientSession;
        this.apiKey = apiKey;
        this.sampleRate = sampleRate;
        this.wsUrl = wsUrl;
        this.minSilenceThreshold = minSilenceThreshold;
        this.maxSilenceThreshold = maxSilenceThreshold;
        this.endOfTurnThreshold = endOfTurnThreshold;

        log.info("Initializing AssemblyAITranscriptionService; wsUrl={} sampleRate={}", wsUrl, sampleRate);
        if (apiKey == null || apiKey.isEmpty())
            log.warn("AssemblyAI API key is empty");

        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        connect();
    }

    private void connect() {
        String url = wsUrl;
        if (!url.contains("?"))
            url = url + "?sample_rate=" + sampleRate;
        // Add AssemblyAI formatting and silence/turn parameters so upstream can perform
        // VAD/formatting
        url += "&punctuate=true&format_turns=true&itn=true";
        url += "&end_of_turn_confidence_threshold=" + endOfTurnThreshold;
        url += "&min_end_of_turn_silence_when_confident=" + minSilenceThreshold;
        url += "&max_turn_silence=" + maxSilenceThreshold;
        log.debug("Connecting to AssemblyAI upstream websocket {} (maskedKey={})", url, maskKey(apiKey));

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .header("User-Agent", "live-transcription-java/1.0");
        if (apiKey != null && !apiKey.isEmpty()) {
            String headerVal = apiKey;
            builder.header("Authorization", headerVal);
        }

        this.upstream = builder.buildAsync(URI.create(url), new Listener() {
            private final StringBuilder sb = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                log.info("Connected to AssemblyAI upstream");
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    String text = sb.toString();
                    sb.setLength(0);
                    // Raw upstream JSON is noisy; keep it at DEBUG level to reduce log volume
                    log.debug("AssemblyAI raw (session={}) -> {}", clientSession != null ? clientSession.getId() : "-",
                            text);
                    // Attempt to parse upstream JSON and normalize to { text, end_of_turn }
                    try {
                        String normalizedJson = normalizeUpstreamToClientPayload(text);
                        // Log the normalized payload before sending to client
                        log.debug("Normalized -> {}", normalizedJson);
                        try {
                            com.fasterxml.jackson.databind.JsonNode outNode = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(normalizedJson);
                            boolean eot = outNode.has("end_of_turn") && outNode.get("end_of_turn").asBoolean(false);
                            if (eot)
                                log.debug("Normalized event end_of_turn=true (session={})",
                                        clientSession != null ? clientSession.getId() : "-");
                        } catch (Exception _parse) {
                            log.debug("Could not parse normalized JSON for logging", _parse);
                        }

                        if (clientSession != null && clientSession.isOpen()) {
                            synchronized (clientSession) {
                                clientSession.sendMessage(
                                        new TextMessage("{\"type\":\"message\",\"data\":" + normalizedJson + "}"));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to forward upstream text to client", e);
                        // fallback: send raw payload inside data
                        try {
                            log.debug("Forwarding raw upstream payload to client: {}", text);
                            if (clientSession != null && clientSession.isOpen()) {
                                synchronized (clientSession) {
                                    clientSession.sendMessage(
                                            new TextMessage("{\"type\":\"message\",\"data\":" + text + "}"));
                                }
                            }
                        } catch (Exception ex) {
                            log.warn("Fallback send failed", ex);
                        }
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                log.debug("Received binary from AssemblyAI upstream ({} bytes)", data.remaining());
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.info("AssemblyAI upstream closed: code={} reason={}", statusCode, reason);
                // If Bearer appears rejected, retry once using raw API key in Authorization
                boolean reasonIndicatesPaddingError = reason != null
                        && reason.toLowerCase().contains("incorrect padding");
                if ((statusCode == 4001 || statusCode == 1008 || reasonIndicatesPaddingError)) {
                    log.info("Connection rejected (status={} reason={}); ", statusCode, reason);
                    return null;
                }
                try {
                    if (clientSession != null && clientSession.isOpen())
                        synchronized (clientSession) {
                            clientSession.close();
                        }
                } catch (Exception e) {
                    log.warn("Error closing client session after upstream close", e);
                }
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("AssemblyAI upstream websocket error", error);
                try {
                    if (clientSession != null && clientSession.isOpen()) {
                        synchronized (clientSession) {
                            clientSession.sendMessage(new TextMessage(
                                    "{\"type\":\"proxy_error\",\"message\":\"" + escape(error.getMessage()) + "\"}"));
                        }
                    }
                } catch (Exception e) {
                    log.warn("Failed to send proxy_error to client", e);
                }
            }
        }).join();
    }

    @Override
    public void sendAudio(ByteBuffer pcm16Chunk) throws Exception {
        // Forward binary audio to AssemblyAI upstream
        if (upstream != null) {
            // ensure payload is read-only buffer
            ByteBuffer dup = pcm16Chunk.asReadOnlyBuffer();
            log.debug("Forwarding {} bytes of audio to AssemblyAI upstream", dup.remaining());
            upstream.sendBinary(dup, true);
        }
    }

    @Override
    public void close() {
        try {
            if (upstream != null)
                upstream.sendClose(1000, "bye");
        } catch (Exception ignored) {
        }
        ex.shutdownNow();
    }

    private String escape(String s) {
        if (s == null)
            return "";
        return s.replace("\"", "\\\"").replace("\n", "\\n");
    }

    private String maskKey(String k) {
        if (k == null)
            return "";
        if (k.length() <= 8)
            return "****";
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }

    // Normalize a variety of AssemblyAI upstream event payloads into a small
    // JSON object with `text` and `end_of_turn` fields that the client expects.
    private String normalizeUpstreamToClientPayload(String upstreamText) {
        if (upstreamText == null || upstreamText.isEmpty())
            return "{}";

        // Try parse as JSON
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(upstreamText);

            String text = null;
            boolean end_of_turn = false;

            // Common AssemblyAI v3 fields
            if (node.has("text"))
                text = node.get("text").asText(null);
            if (node.has("utterance"))
                text = text == null ? node.get("utterance").asText(null) : text;
            if (node.has("punctuated") && node.get("punctuated").has("transcript")) {
                text = node.get("punctuated").get("transcript").asText(text == null ? "" : text);
            }

            // If no top-level text/utterance/transcript present, attempt to join words into
            // a partial
            if ((text == null || text.isEmpty()) && node.has("words") && node.get("words").isArray()) {
                StringBuilder sbWords = new StringBuilder();
                for (com.fasterxml.jackson.databind.JsonNode w : node.get("words")) {
                    if (w.has("text")) {
                        String wt = w.get("text").asText("");
                        if (!wt.isEmpty()) {
                            if (sbWords.length() > 0)
                                sbWords.append(' ');
                            sbWords.append(wt);
                        }
                    }
                }
                if (sbWords.length() > 0)
                    text = sbWords.toString();
            }

            // Flags indicating final/turn end
            if (node.has("is_final"))
                end_of_turn = node.get("is_final").asBoolean(false);
            if (node.has("final"))
                end_of_turn = end_of_turn || node.get("final").asBoolean(false);
            if (node.has("end_of_turn"))
                end_of_turn = end_of_turn || node.get("end_of_turn").asBoolean(false);
            if (node.has("type") && node.get("type").asText().equalsIgnoreCase("message") && node.has("text")) {
                // some AssemblyAI messages place text directly
                end_of_turn = end_of_turn || false;
            }

            // Fallback: if top-level contains a single string payload
            if (text == null && node.isTextual())
                text = node.asText();

            com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
            if (text != null)
                out.put("text", text);
            out.put("end_of_turn", end_of_turn);
            if (node.has("audio_start")) {
                out.put("audio_start", node.get("audio_start").asLong());
            }
            if (node.has("audio_end")) {
                out.put("audio_end", node.get("audio_end").asLong());
            }
            if (node.has("words") && node.get("words").isArray()) {
                out.set("words", node.get("words"));
            }
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            log.debug("normalizeUpstreamToClientPayload parse failed, returning raw string", e);
            // escape the raw string into JSON string
            String escaped = upstreamText.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
            return "{\"text\":\"" + escaped + "\",\"end_of_turn\":false}";
        }
    }
}
