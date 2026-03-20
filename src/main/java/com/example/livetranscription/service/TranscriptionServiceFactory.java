package com.example.livetranscription.service;

import com.example.livetranscription.config.TranscriptionConfig;
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
    private final TranscriptionNormalizationService normalizationService;

    public TranscriptionServiceFactory(
            @Value("${assemblyai.api-key:}") String assemblyKey,
            TranscriptionNormalizationService normalizationService) {
        this.assemblyKey = assemblyKey;
        this.normalizationService = normalizationService;
        
        boolean keyPresent = (assemblyKey != null && !assemblyKey.isEmpty());
        if (!keyPresent) {
            log.warn("********************************************************************************");
            log.warn("WARNING: AssemblyAI API Key is MISSING!");
            log.warn("Real-time transcription will NOT be functional.");
            log.warn("The system will use a Mock/Stub service (simulated responses) for transcription.");
            log.warn("Please provide 'ASSEMBLY_AI_API_KEY' in your environment variables.");
            log.warn("********************************************************************************");
        } else {
            log.info("TranscriptionServiceFactory initialized (assemblyWsUrl={}, sampleRate={}, assemblyKeyPresent=true)",
                    TranscriptionConfig.ASSEMBLY_AI_WS_URL, TranscriptionConfig.SAMPLE_RATE);
        }
    }

    public int getSampleRate() {
        return TranscriptionConfig.SAMPLE_RATE;
    }

    public int getMinSilenceThreshold() {
        return TranscriptionConfig.MIN_SILENCE_THRESHOLD;
    }

    public int getMaxSilenceThreshold() {
        return TranscriptionConfig.MAX_SILENCE_THRESHOLD;
    }

    public double getEndOfTurnThreshold() {
        return TranscriptionConfig.END_OF_TURN_THRESHOLD;
    }

    public TranscriptionService createForSession(WebSocketSession session) {
        if (assemblyKey != null && !assemblyKey.isEmpty()) {
            try {
                log.info("Creating AssemblyAITranscriptionService (maskedKey={})", maskKey(assemblyKey));
                return new AssemblyAITranscriptionService(session, assemblyKey, TranscriptionConfig.SAMPLE_RATE, 
                        TranscriptionConfig.ASSEMBLY_AI_WS_URL, normalizationService);
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
                    } catch (Exception ignored) {
                    }
                });
            }

            @Override
            public void close() {
                ex.shutdownNow();
            }
        };
    }

    private String maskKey(String k) {
        if (k == null)
            return "";
        if (k.length() <= 8)
            return "****";
        return k.substring(0, 4) + "..." + k.substring(k.length() - 4);
    }
}
