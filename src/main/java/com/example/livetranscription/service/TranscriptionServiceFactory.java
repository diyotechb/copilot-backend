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

    public TranscriptionServiceFactory(@Value("${assemblyai.api-key:}") String assemblyKey) {
        this.assemblyKey = assemblyKey;

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

    public TranscriptionService createDirectForSession(WebSocketSession session) {
        int rate = extractSampleRate(session);

        if (assemblyKey != null && !assemblyKey.isEmpty()) {
            try {
                log.info("Creating AssemblyAIDirectService (maskedKey={}, rate={})", maskKey(assemblyKey), rate);
                return new AssemblyAIDirectService(session, assemblyKey, rate,
                        TranscriptionConfig.ASSEMBLY_AI_WS_URL);
            } catch (Exception e) {
                log.error("Failed to create AssemblyAI direct adapter, falling back to stub", e);
            }
        }

        return createStub(session, rate);
    }

    private int extractSampleRate(WebSocketSession session) {
        if (session == null || session.getUri() == null) return TranscriptionConfig.SAMPLE_RATE;
        String query = session.getUri().getQuery();
        if (query == null) return TranscriptionConfig.SAMPLE_RATE;

        try {
            for (String param : query.split("&")) {
                String[] pair = param.split("=");
                if (pair.length == 2 && "sample_rate".equalsIgnoreCase(pair[0])) {
                    return Integer.parseInt(pair[1]);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse sample_rate from URI: {}", query);
        }
        return TranscriptionConfig.SAMPLE_RATE;
    }

    private TranscriptionService createStub(WebSocketSession session, int rate) {
        log.info("Using stub TranscriptionService for session {} at {}Hz", session != null ? session.getId() : "-null-", rate);
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
