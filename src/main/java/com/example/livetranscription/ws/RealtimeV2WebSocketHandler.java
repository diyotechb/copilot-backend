package com.example.livetranscription.ws;

import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.model.ClientMessage;
import com.example.livetranscription.service.TranscriptionService;
import com.example.livetranscription.service.TranscriptionServiceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * WebSocket handler for the /realtime-v2 endpoint.
 * Uses AssemblyAIDirectService (no server-side accumulation).
 */
@Component
public class RealtimeV2WebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeV2WebSocketHandler.class);

    private final TranscriptionServiceFactory factory;
    private final Map<WebSocketSession, TranscriptionService> services = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    public RealtimeV2WebSocketHandler(TranscriptionServiceFactory factory) {
        this.factory = factory;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("New V2 websocket connection: sessionId={} uri={}", session.getId(), session.getUri());
        TranscriptionService svc = factory.createDirectForSession(session);
        services.put(session, svc);

        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(new ClientMessage("open", null))));

            Map<String, Object> params = Map.of(
                "sample_rate", factory.getSampleRate(),
                "min_silence_threshold", TranscriptionConfig.V2_MIN_SILENCE_THRESHOLD,
                "max_turn_silence", TranscriptionConfig.V2_MAX_TURN_SILENCE,
                "end_of_turn_threshold", TranscriptionConfig.V2_END_OF_TURN_THRESHOLD,
                "vad_threshold", TranscriptionConfig.V2_VAD_THRESHOLD
            );
            session.sendMessage(new TextMessage(mapper.writeValueAsString(new ClientMessage("proxy_open", params))));
        }
    }

    @Override
    public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
        TranscriptionService svc = services.get(session);
        if (svc == null) return;

        if (message instanceof BinaryMessage) {
            BinaryMessage bm = (BinaryMessage) message;
            ByteBuffer payload = bm.getPayload();
            log.debug("V2: Received binary {} ({} bytes)", session.getId(), payload.remaining());
            svc.sendAudio(payload);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("V2 transport error on session {}", session != null ? session.getId() : "-", exception);
        try {
            if (session.isOpen()) {
                ClientMessage errMsg = ClientMessage.error(exception.getMessage());
                synchronized (session) {
                    session.sendMessage(new TextMessage(mapper.writeValueAsString(errMsg)));
                }
            }
        } catch (IOException e) {
            log.warn("Failed to send proxy_error to client", e);
        }
        closeSession(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("V2 session closed: sessionId={} code={}", session != null ? session.getId() : "-",
                status != null ? status.getCode() : -1);
        TranscriptionService svc = services.remove(session);
        if (svc != null) svc.close();
    }

    private void closeSession(WebSocketSession session) {
        try { if (session != null && session.isOpen()) session.close(); } catch (IOException ignored) {}
    }
}
