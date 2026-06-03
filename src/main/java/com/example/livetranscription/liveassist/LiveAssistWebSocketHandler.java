package com.example.livetranscription.liveassist;

import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.model.ClientMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LiveAssistWebSocketHandler extends AbstractWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(LiveAssistWebSocketHandler.class);

    private final LiveAssistContextStore contextStore;
    private final SessionRegistry registry;
    private final AiResponseStreamer aiStreamer;
    private final LiveAssistSessionStore sessionStore;
    private final String assemblyKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<WebSocketSession, SessionState> states = new ConcurrentHashMap<>();

    public LiveAssistWebSocketHandler(LiveAssistContextStore contextStore,
                                          SessionRegistry registry,
                                          AiResponseStreamer aiStreamer,
                                          LiveAssistSessionStore sessionStore,
                                          @Value("${assemblyai.api-key}") String assemblyKey) {
        this.contextStore = contextStore;
        this.registry = registry;
        this.aiStreamer = aiStreamer;
        this.sessionStore = sessionStore;
        this.assemblyKey = assemblyKey;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        Map<String, String> qs = parseQuery(session.getUri());
        String conversationId = qs.getOrDefault("conversationId", UUID.randomUUID().toString());
        int sampleRate = parseInt(qs.get("sample_rate"), TranscriptionConfig.SAMPLE_RATE);

        contextStore.getOrCreate(conversationId);
        registry.register(conversationId, session);
        states.put(session, new SessionState(conversationId, sampleRate));

        log.info("Interview WS open conv={} sample_rate={} session={}", conversationId, sampleRate, session.getId());

        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(
                    new ClientMessage("open", Map.of("conversationId", conversationId)))));
            session.sendMessage(new TextMessage(mapper.writeValueAsString(
                    new ClientMessage("proxy_open", Map.of(
                            "sample_rate", sampleRate,
                            "end_of_turn_threshold", TranscriptionConfig.END_OF_TURN_THRESHOLD)))));
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        if (!(message instanceof BinaryMessage bm)) return;
        SessionState state = states.get(session);
        if (state == null) return;

        LiveAssistConversationContext ctx = contextStore.getOrCreate(state.conversationId);
        if (!ctx.isSessionBuilt()) {
            if (!state.notBuiltNotified) {
                state.notBuiltNotified = true;
                synchronized (session) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(
                            ClientMessage.error("Start the session on the Live Assist page before streaming."))));
                }
            }
            return;
        }

        LiveAssistAssemblyAIService svc = state.ensureService(ctx, registry, aiStreamer, assemblyKey);
        svc.sendAudio(bm.getPayload());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Interview WS transport error session={}", session != null ? session.getId() : "-", exception);
        try {
            if (session != null && session.isOpen()) {
                String json = mapper.writeValueAsString(ClientMessage.error(exception.getMessage()));
                synchronized (session) {
                    session.sendMessage(new TextMessage(json));
                }
            }
        } catch (IOException ignored) {
        }
        closeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        SessionState state = states.remove(session);
        log.info("Interview WS closed session={} code={} conv={}",
                session != null ? session.getId() : "-",
                status != null ? status.getCode() : -1,
                state != null ? state.conversationId : "-");
        if (state != null) {
            registry.unregister(state.conversationId, session);
            state.closeService();
            markEnded(state.conversationId);
        }
    }

    private void markEnded(String conversationId) {
        LiveAssistConversationContext ctx = contextStore.getOrCreate(conversationId);
        String sessionId = ctx.getStorageSessionId();
        if (sessionId == null || sessionId.isBlank()) return;

        CompletableFuture.runAsync(() -> {
            try {
                LiveAssistSession s = sessionStore.get(sessionId);
                if (s != null && !"COMPLETED".equals(s.getStatus()) && !"ENDED".equals(s.getStatus())) {
                    s.setStatus("ENDED");
                    sessionStore.save(s);
                }
            } catch (Exception e) {
                log.warn("Failed to mark session {} ENDED on close", sessionId, e);
            }
        });
    }

    private void closeSession(WebSocketSession session) {
        try { if (session != null && session.isOpen()) session.close(); } catch (IOException ignored) {}
    }

    private static Map<String, String> parseQuery(URI uri) {
        if (uri == null || uri.getQuery() == null) return Map.of();
        Map<String, String> out = new java.util.HashMap<>();
        for (String pair : uri.getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    private static int parseInt(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return fallback; }
    }

    private static final class SessionState {
        final String conversationId;
        final int sampleRate;
        volatile LiveAssistAssemblyAIService service;
        volatile boolean notBuiltNotified;

        SessionState(String conversationId, int sampleRate) {
            this.conversationId = conversationId;
            this.sampleRate = sampleRate;
        }

        synchronized LiveAssistAssemblyAIService ensureService(ConversationContext ctx,
                                                           SessionRegistry registry,
                                                           AiResponseStreamer aiStreamer,
                                                           String apiKey) {
            if (service == null) {
                service = new LiveAssistAssemblyAIService(ctx, registry, aiStreamer, apiKey, sampleRate,
                        TranscriptionConfig.ASSEMBLY_AI_WS_URL);
            }
            return service;
        }

        void closeService() {
            if (service != null) service.close();
        }
    }
}
