package com.example.livetranscription.voice;

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
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class VoiceAssemblyAIService {

    private static final Logger log = LoggerFactory.getLogger(VoiceAssemblyAIService.class);
    private static final long CONNECT_WAIT_SECONDS = 10L;

    private final ConversationContext context;
    private final SessionRegistry registry;
    private final AiResponseStreamer aiStreamer;
    private final HttpClient httpClient;
    private final String apiKey;
    private final int sampleRate;
    private final String wsUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Object connectLock = new Object();
    private volatile CompletableFuture<WebSocket> upstreamFuture;
    private volatile boolean closed = false;
    private volatile long lastTriggeredTurnOrder = -1L;

    public VoiceAssemblyAIService(ConversationContext context,
                                  SessionRegistry registry,
                                  AiResponseStreamer aiStreamer,
                                  String apiKey,
                                  int sampleRate,
                                  String wsUrl) {
        this.context = context;
        this.registry = registry;
        this.aiStreamer = aiStreamer;
        this.apiKey = apiKey;
        this.sampleRate = sampleRate;
        this.wsUrl = wsUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public void sendAudio(ByteBuffer pcm16Chunk) {
        if (closed) return;
        WebSocket ws = ensureConnected();
        if (ws == null) return;
        ws.sendBinary(pcm16Chunk.asReadOnlyBuffer(), true);
    }

    public void close() {
        closed = true;
        CompletableFuture<WebSocket> f = upstreamFuture;
        if (f == null) return;
        f.thenAccept(ws -> {
            try { ws.sendClose(1000, "bye"); } catch (Exception ignored) {}
        }).exceptionally(t -> null);
    }

    private WebSocket ensureConnected() {
        CompletableFuture<WebSocket> f = upstreamFuture;
        if (f == null) {
            synchronized (connectLock) {
                if (closed) return null;
                if (upstreamFuture == null) {
                    upstreamFuture = connectAsync();
                }
                f = upstreamFuture;
            }
        }
        try {
            return f.get(CONNECT_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            log.warn("AssemblyAI upstream connect timed out");
            return null;
        } catch (Exception e) {
            log.warn("AssemblyAI upstream connect failed", e);
            return null;
        }
    }

    private CompletableFuture<WebSocket> connectAsync() {
        String url = wsUrl;
        if (!url.contains("?")) url = url + "?sample_rate=" + sampleRate;
        url += "&speech_model=" + TranscriptionConfig.SPEECH_MODEL;
        url += "&format_turns=true";
        url += "&encoding=pcm_s16le";
        url += "&end_of_turn_confidence_threshold=" + TranscriptionConfig.END_OF_TURN_THRESHOLD;
        url += "&min_end_of_turn_silence_when_confident=" + TranscriptionConfig.MIN_SILENCE_THRESHOLD;
        url += "&max_turn_silence=" + TranscriptionConfig.MAX_TURN_SILENCE;
        url += "&vad_threshold=" + TranscriptionConfig.VAD_THRESHOLD;
        log.debug("Voice: connecting AssemblyAI {} (maskedKey={})", url, BackendUtils.maskKey(apiKey));

        WebSocket.Builder builder = httpClient.newWebSocketBuilder()
                .header("User-Agent", "voice-copilot-java/1.0");
        if (apiKey != null && !apiKey.isEmpty()) {
            builder.header("Authorization", apiKey);
        }

        return builder.buildAsync(URI.create(url), new Listener() {
            private final StringBuilder sb = new StringBuilder();

            @Override
            public void onOpen(WebSocket webSocket) {
                log.info("Voice: AssemblyAI upstream connected conv={}", context.getId());
                webSocket.request(1);
            }

            @Override
            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                sb.append(data);
                if (last) {
                    String raw = sb.toString();
                    sb.setLength(0);
                    handleUpstreamText(raw);
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
            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                log.info("Voice: AssemblyAI upstream closed code={} reason={}", statusCode, reason);
                return null;
            }

            @Override
            public void onError(WebSocket webSocket, Throwable error) {
                log.error("Voice: AssemblyAI upstream error", error);
                broadcastError(error.getMessage());
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void handleUpstreamText(String rawJson) {
        try {
            Map<String, Object> raw = mapper.readValue(rawJson, Map.class);
            if (!"Turn".equals(raw.get("type"))) return;

            broadcastTurn(raw);
            maybeTriggerAi(raw);
        } catch (Exception e) {
            log.error("Voice: failed to process upstream Turn", e);
        }
    }

    private void broadcastTurn(Map<String, Object> raw) throws Exception {
        String payload = mapper.writeValueAsString(new ClientMessage("message", raw));
        for (WebSocketSession s : registry.sessionsFor(context.getId())) {
            if (s == null || !s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(payload));
                }
            } catch (Exception e) {
                log.warn("Failed to send Turn to session {}", s.getId(), e);
            }
        }
    }

    private void maybeTriggerAi(Map<String, Object> raw) {
        boolean endOfTurn = Boolean.TRUE.equals(raw.get("end_of_turn"));
        boolean formatted = Boolean.TRUE.equals(raw.get("turn_is_formatted"));
        if (!endOfTurn || !formatted) return;

        long turnOrder = ((Number) raw.getOrDefault("turn_order", -1)).longValue();
        if (turnOrder == lastTriggeredTurnOrder) return;
        lastTriggeredTurnOrder = turnOrder;

        String transcript = (String) raw.get("transcript");
        aiStreamer.handleTranscript(context, transcript);
    }

    private void broadcastError(String msg) {
        try {
            String json = mapper.writeValueAsString(ClientMessage.error(msg));
            for (WebSocketSession s : registry.sessionsFor(context.getId())) {
                if (s == null || !s.isOpen()) continue;
                try {
                    synchronized (s) {
                        s.sendMessage(new TextMessage(json));
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }
}
