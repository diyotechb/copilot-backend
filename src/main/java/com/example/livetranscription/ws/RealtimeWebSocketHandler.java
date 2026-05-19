package com.example.livetranscription.ws;

import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.model.ClientMessage;
import com.example.livetranscription.service.TranscriptionService;
import com.example.livetranscription.service.TranscriptionServiceFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WebSocket handler for the /api/realtime-transcribe endpoint.
 * Uses AssemblyAIDirectService (no server-side accumulation).
 */
@Component
public class RealtimeWebSocketHandler extends AbstractWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeWebSocketHandler.class);

    private final TranscriptionServiceFactory factory;
    private final Map<WebSocketSession, TranscriptionService> services = new ConcurrentHashMap<>();
    private final Map<WebSocketSession, ScheduledFuture<?>> sessionTimeouts = new ConcurrentHashMap<>();
    private final ObjectMapper mapper = new ObjectMapper();

    // Shared single-thread scheduler: only fires once per session after the
    // 4-hour cap. Even at peak (e.g., 50 concurrent sessions) firings are
    // rare and quick, so one thread is plenty.
    private final ScheduledExecutorService timeoutScheduler;

    public RealtimeWebSocketHandler(TranscriptionServiceFactory factory) {
        this.factory = factory;
        AtomicLong threadIdx = new AtomicLong();
        this.timeoutScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "ws-session-timeout-" + threadIdx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("New websocket connection: sessionId={} path={}", session.getId(),
                session.getUri() != null ? session.getUri().getPath() : "-");
        TranscriptionService svc = factory.createForSession(session);
        services.put(session, svc);

        // Hard cap on session lifetime. Pure safety net for tabs that stay
        // open with a hot mic — the upstream connection itself is opened
        // lazily on the first audio frame, so a quiet tab costs nothing.
        ScheduledFuture<?> timeout = timeoutScheduler.schedule(
                () -> closeForMaxDuration(session),
                TranscriptionConfig.MAX_SESSION_DURATION_HOURS,
                TimeUnit.HOURS);
        sessionTimeouts.put(session, timeout);

        synchronized (session) {
            session.sendMessage(new TextMessage(mapper.writeValueAsString(new ClientMessage("open", null))));

            Map<String, Object> params = Map.of(
                "sample_rate", factory.getSampleRate(),
                "min_silence_threshold", TranscriptionConfig.MIN_SILENCE_THRESHOLD,
                "max_turn_silence", TranscriptionConfig.MAX_TURN_SILENCE,
                "end_of_turn_threshold", TranscriptionConfig.END_OF_TURN_THRESHOLD,
                "vad_threshold", TranscriptionConfig.VAD_THRESHOLD
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
            log.debug("Received binary from {} ({} bytes)", session.getId(), payload.remaining());
            svc.sendAudio(payload);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("Transport error on session {}", session != null ? session.getId() : "-", exception);
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
        log.info("Session closed: sessionId={} code={}", session != null ? session.getId() : "-",
                status != null ? status.getCode() : -1);
        ScheduledFuture<?> timeout = sessionTimeouts.remove(session);
        if (timeout != null) timeout.cancel(false);
        TranscriptionService svc = services.remove(session);
        if (svc != null) svc.close();
    }

    private void closeForMaxDuration(WebSocketSession session) {
        try {
            if (session != null && session.isOpen()) {
                log.info("Closing WS session after max duration ({}h): sessionId={}",
                        TranscriptionConfig.MAX_SESSION_DURATION_HOURS, session.getId());
                CloseStatus status = new CloseStatus(CloseStatus.NORMAL.getCode(), "session_max_duration");
                synchronized (session) {
                    session.close(status);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to close session after max duration", e);
        }
    }

    private void closeSession(WebSocketSession session) {
        try { if (session != null && session.isOpen()) session.close(); } catch (IOException ignored) {}
    }

    @PreDestroy
    public void shutdown() {
        timeoutScheduler.shutdown();
        try {
            if (!timeoutScheduler.awaitTermination(2, TimeUnit.SECONDS)) {
                timeoutScheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            timeoutScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
