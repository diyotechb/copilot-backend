package com.example.livetranscription.service;

import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.model.ClientMessage;
import com.example.livetranscription.util.BackendUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Direct pass-through adapter for AssemblyAI V3 streaming.
 * No server-side accumulation — AssemblyAI V3 with format_turns=true sends
 * cumulative turn text natively, so the frontend handles all display logic.
 */
public class AssemblyAIDirectService implements TranscriptionService {
    private static final Logger log = LoggerFactory.getLogger(AssemblyAIDirectService.class);

    private volatile WebSocket upstream;
    private final WebSocketSession clientSession;
    private final HttpClient httpClient;
    private final String apiKey;
    private final int sampleRate;
    private final String wsUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssemblyAIDirectService(WebSocketSession clientSession, String apiKey, int sampleRate, String wsUrl) {
        this.clientSession = clientSession;
        this.apiKey = apiKey;
        this.sampleRate = sampleRate;
        this.wsUrl = wsUrl;

        log.info("Initializing AssemblyAIDirectService (no accumulation); wsUrl={} sampleRate={}", wsUrl, sampleRate);
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
        url += "&speech_model=" + TranscriptionConfig.SPEECH_MODEL;
        url += "&format_turns=true";
        url += "&encoding=pcm_s16le";
        url += "&end_of_turn_confidence_threshold=" + TranscriptionConfig.END_OF_TURN_THRESHOLD;
        url += "&min_end_of_turn_silence_when_confident=" + TranscriptionConfig.MIN_SILENCE_THRESHOLD;
        url += "&max_turn_silence=" + TranscriptionConfig.MAX_TURN_SILENCE;
        url += "&vad_threshold=" + TranscriptionConfig.VAD_THRESHOLD;
        log.debug("Connecting to AssemblyAI (direct) {} (maskedKey={})", url, BackendUtils.maskKey(apiKey));

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .header("User-Agent", "live-transcription-java/1.0");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", apiKey);
        }

        this.upstream = builder.buildAsync(URI.create(url), new Listener() {
            private final StringBuilder sb = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                log.info("Connected to AssemblyAI upstream (direct)");
                webSocket.request(1);
            }

            @SuppressWarnings("unchecked")
            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    String rawJson = sb.toString();
                    sb.setLength(0);
                    try {
                        // Pass raw JSON map directly — skip normalization entirely.
                        // AssemblyAI V3 Turn fields: type, transcript, utterance, words,
                        // end_of_turn, turn_is_formatted, end_of_turn_confidence, turn_order
                        java.util.Map<String, Object> raw = mapper.readValue(rawJson, java.util.Map.class);
                        String msgType = (String) raw.get("type");

                        // Only forward Turn messages; ignore Begin/Termination
                        if ("Turn".equals(msgType) && clientSession != null && clientSession.isOpen()) {
                            String payload = mapper.writeValueAsString(new ClientMessage("message", raw));
                            synchronized (clientSession) {
                                clientSession.sendMessage(new TextMessage(payload));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to process upstream text", e);
                    }
                }
                webSocket.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
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
                log.info("AssemblyAI upstream (direct) closed: code={} reason={}", statusCode, reason);
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
                log.error("AssemblyAI upstream (direct) websocket error", error);
                try {
                    if (clientSession != null && clientSession.isOpen()) {
                        ClientMessage errMsg = ClientMessage.error(error.getMessage());
                        synchronized (clientSession) {
                            clientSession.sendMessage(new TextMessage(mapper.writeValueAsString(errMsg)));
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
        if (upstream != null) {
            ByteBuffer dup = pcm16Chunk.asReadOnlyBuffer();
            log.debug("Forwarding {} bytes to AssemblyAI (direct)", dup.remaining());
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
    }
}
