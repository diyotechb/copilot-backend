package com.example.livetranscription.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
public class TranscriptionServiceFactory {
    private static final Logger log = LoggerFactory.getLogger(TranscriptionServiceFactory.class);

    private final String assemblyKey;
    private final int sampleRate;
    private final String assemblyWsUrl;

    private final int minSilenceThreshold;
    private final int maxSilenceThreshold;
    private final double endOfTurnThreshold;

    private final TranscriptionNormalizationService normalizationService;

    public TranscriptionServiceFactory(
            @Value("${assemblyai.api-key:}") String assemblyKey,
            @Value("${assemblyai.sample-rate:48000}") int sampleRate,
            @Value("${assemblyai.ws-url:wss://api.assemblyai.com/v2/realtime/ws}") String assemblyWsUrl,
            @Value("${silence.min-threshold:500}") int minSilenceThreshold,
            @Value("${silence.max-threshold:2000}") int maxSilenceThreshold,
            @Value("${silence.end-of-turn-threshold:0.3}") double endOfTurnThreshold,
            TranscriptionNormalizationService normalizationService) {
        this.assemblyKey = assemblyKey;
        this.sampleRate = sampleRate;
        this.assemblyWsUrl = assemblyWsUrl;
        this.minSilenceThreshold = minSilenceThreshold;
        this.maxSilenceThreshold = maxSilenceThreshold;
        this.endOfTurnThreshold = endOfTurnThreshold;
        this.normalizationService = normalizationService;
        log.info("TranscriptionServiceFactory initialized (assemblyWsUrl={}, sampleRate={}, assemblyKeyPresent={}, minSilence={}, maxSilence={}, endOfTurn={})",
            assemblyWsUrl, sampleRate, (assemblyKey != null && !assemblyKey.isEmpty()), minSilenceThreshold, maxSilenceThreshold, endOfTurnThreshold);
    }

    public int getSampleRate() {
        return this.sampleRate;
    }

    public int getMinSilenceThreshold() {
        return this.minSilenceThreshold;
    }

    public int getMaxSilenceThreshold() {
        return this.maxSilenceThreshold;
    }

    public double getEndOfTurnThreshold() {
        return this.endOfTurnThreshold;
    }

    public TranscriptionService createForSession(WebSocketSession session) {
        if (assemblyKey != null && !assemblyKey.isEmpty()) {
            try {
                log.info("Creating AssemblyAITranscriptionService (maskedKey={})", maskKey(assemblyKey));
                return new AssemblyAITranscriptionService(session, assemblyKey, sampleRate, assemblyWsUrl,
                    minSilenceThreshold, maxSilenceThreshold, endOfTurnThreshold, normalizationService);
            } catch (Exception e) {
                log.error("Failed to create AssemblyAI adapter, falling back to stub", e);
            }
        }

        return createStub(session);
    }

    private TranscriptionService createStub(WebSocketSession session) {
        log.info("Using stub TranscriptionService for session {}", session != null ? session.getId() : "-null-");
        return new TranscriptionService() {
            private final ExecutorService ex = Executors.newSingleThreadExecutor();

            @Override
            public void sendAudio(ByteBuffer pcm16Chunk) {
                ex.submit(() -> {
                    try {
                        if (session != null && session.isOpen()) {
                            String json = "{\"type\":\"message\",\"data\":{\"text\":\"(simulated partial)\"}}";
                            session.sendMessage(new TextMessage(json));
                        }
                    } catch (Exception ignored) {}
                });
            }

            @Override
            public void close() {
                ex.shutdownNow();
            }
        };
    }

    private String maskKey(String k) {
        if (k == null) return "";
        if (k.length() <= 8) return "****";
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }
}
