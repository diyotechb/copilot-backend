package com.example.service.impl;

import com.example.dynamodb.DynamoPersistenceService;
import com.example.model.InterviewMessage;
import com.example.model.InterviewSession;
import com.example.openai.OpenAIConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.function.Consumer;

@Service
public class InterviewWebSocketSessionService {
    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;
    @Autowired
    private OpenAIConversationService openAIConversationService;

    public void handleCandidateMessage(String sessionId, String message, Consumer<String> streamTokenConsumer) {
        InterviewSession session = dynamoPersistenceService.getSession(sessionId);
        if (session == null)
            throw new IllegalArgumentException("Session not found");
        openAIConversationService.appendUserMessage(session.getConversationId(), message);
        InterviewMessage userMsg = new InterviewMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setTimestamp(Instant.now());
        userMsg.setRole("user");
        userMsg.setContent(message);
        dynamoPersistenceService.saveMessage(userMsg);
        openAIConversationService.streamAssistantResponse(session.getConversationId(), token -> {
            InterviewMessage aiMsg = new InterviewMessage();
            aiMsg.setSessionId(sessionId);
            aiMsg.setTimestamp(Instant.now());
            aiMsg.setRole("assistant");
            aiMsg.setContent(token);
            dynamoPersistenceService.saveMessage(aiMsg);
            streamTokenConsumer.accept(token);
        });
    }
}
