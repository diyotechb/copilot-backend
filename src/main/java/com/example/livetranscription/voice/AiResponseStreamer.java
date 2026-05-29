package com.example.livetranscription.voice;

import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.ClientMessage;
import com.example.livetranscription.model.InterviewMessage;
import com.example.livetranscription.service.openai.OpenAiChatService.Message;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Component
public class AiResponseStreamer {
    private static final Logger log = LoggerFactory.getLogger(AiResponseStreamer.class);

    private final VoiceOpenAiStreamService openAiStreamService;
    private final SessionRegistry registry;
    private final DynamoPersistenceService persistence;
    private final ObjectMapper mapper = new ObjectMapper();

    public AiResponseStreamer(VoiceOpenAiStreamService openAiStreamService,
                              SessionRegistry registry,
                              DynamoPersistenceService persistence) {
        this.openAiStreamService = openAiStreamService;
        this.registry = registry;
        this.persistence = persistence;
    }

    public void handleTranscript(ConversationContext context, String transcript) {
        if (transcript == null || transcript.isBlank()) return;

        List<Message> messages = context.buildMessages(transcript);
        StringBuilder full = new StringBuilder();

        openAiStreamService.stream(messages)
                .doOnNext(chunk -> {
                    full.append(chunk);
                    broadcastChunk(context.getId(), chunk, false);
                })
                .doOnComplete(() -> {
                    broadcastChunk(context.getId(), "", true);
                    context.recordExchange(transcript, full.toString());
                    persistExchange(context, transcript, full.toString());
                })
                .doOnError(err -> {
                    log.warn("AI stream error conv={} msg={}", context.getId(), err.getMessage());
                    broadcastError(context.getId(), err.getMessage());
                })
                .subscribe();
    }

    private void persistExchange(ConversationContext context, String question, String answer) {
        if (!(context instanceof InterviewConversationContext ic)) return;
        String sessionId = ic.getStorageSessionId();
        if (sessionId == null || sessionId.isBlank()) return;
        if (question == null || question.isBlank()) return;

        CompletableFuture.runAsync(() -> {
            try {
                Instant now = Instant.now();
                persistence.saveMessage(message(sessionId, now, "user", question));
                // +1ms keeps the assistant row from colliding with the question on the (sessionId, timestamp) key
                persistence.saveMessage(message(sessionId, now.plusMillis(1), "assistant", answer));
            } catch (Exception e) {
                log.warn("Failed to persist exchange for session {}", sessionId, e);
            }
        });
    }

    private InterviewMessage message(String sessionId, Instant ts, String role, String content) {
        InterviewMessage m = new InterviewMessage();
        m.setSessionId(sessionId);
        m.setTimestamp(ts);
        m.setRole(role);
        m.setContent(content);
        return m;
    }

    private void broadcastChunk(String conversationId, String content, boolean completed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("type", "AI_RESPONSE");
        payload.put("sessionId", conversationId);
        payload.put("content", content);
        payload.put("completed", completed);
        String json;
        try { json = mapper.writeValueAsString(payload); }
        catch (Exception e) { log.warn("AI_RESPONSE serialize failed", e); return; }
        broadcast(conversationId, json);
    }

    private void broadcastError(String conversationId, String msg) {
        try {
            String json = mapper.writeValueAsString(ClientMessage.error(msg));
            broadcast(conversationId, json);
        } catch (Exception ignored) {
        }
    }

    private void broadcast(String conversationId, String json) {
        for (WebSocketSession s : registry.sessionsFor(conversationId)) {
            if (s == null || !s.isOpen()) continue;
            try {
                synchronized (s) {
                    s.sendMessage(new TextMessage(json));
                }
            } catch (Exception e) {
                log.warn("Failed to send to session {}", s.getId(), e);
            }
        }
    }
}
