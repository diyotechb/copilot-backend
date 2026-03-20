package com.example.livetranscription.service;

import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.model.AssemblyAIResponse;
import com.example.livetranscription.model.ClientMessage;
import com.example.livetranscription.model.TranscriptionData;
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

    private static final int MIN_SILENCE_THRESHOLD = TranscriptionConfig.MIN_SILENCE_THRESHOLD;
    private static final int MAX_SILENCE_THRESHOLD = TranscriptionConfig.MAX_SILENCE_THRESHOLD;
    private static final double END_OF_TURN_THRESHOLD = TranscriptionConfig.END_OF_TURN_THRESHOLD;

    private volatile WebSocket upstream;
    private final WebSocketSession clientSession;
    private final ExecutorService ex = Executors.newSingleThreadExecutor();
    private final HttpClient httpClient;
    private final String apiKey;
    private final int sampleRate;
    private final String wsUrl;
    private final TranscriptionNormalizationService normalizationService;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssemblyAITranscriptionService(WebSocketSession clientSession, String apiKey, int sampleRate, String wsUrl,
            TranscriptionNormalizationService normalizationService) {
        this.clientSession = clientSession;
        this.apiKey = apiKey;
        this.sampleRate = sampleRate;
        this.wsUrl = wsUrl;
        this.normalizationService = normalizationService;

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
        url += "&speech_model=" + TranscriptionConfig.SPEECH_MODEL;
        url += "&punctuate=true&format_turns=true&itn=true";
        // end_of_turn parameters are supported in V3 for turn detection
        url += "&end_of_turn_confidence_threshold=" + END_OF_TURN_THRESHOLD;
        url += "&min_end_of_turn_silence_when_confident=" + MIN_SILENCE_THRESHOLD;
        url += "&max_turn_silence=" + MAX_SILENCE_THRESHOLD;
        log.debug("Connecting to AssemblyAI upstream websocket {} (maskedKey={})", url, BackendUtils.maskKey(apiKey));

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
                        AssemblyAIResponse response = mapper.readValue(text, AssemblyAIResponse.class);
                        TranscriptionData transcriptionData = normalizationService.normalize(response);
                        
                        if (clientSession != null && clientSession.isOpen()) {
                            ClientMessage msg = new ClientMessage("message", transcriptionData);
                            synchronized (clientSession) {
                                clientSession.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
                            }
                        }
                    } catch (Exception e) {
                        log.error("Failed to forward upstream text to client", e);
                        // fallback: send raw payload inside data
                        try {
                            log.debug("Forwarding raw upstream payload to client: {}", text);
                            if (clientSession != null && clientSession.isOpen()) {
                                ClientMessage msg = new ClientMessage("message", mapper.readTree(text));
                                synchronized (clientSession) {
                                    clientSession.sendMessage(new TextMessage(mapper.writeValueAsString(msg)));
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

}
