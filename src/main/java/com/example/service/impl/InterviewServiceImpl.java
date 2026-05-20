package com.example.service.impl;

import com.example.model.InterviewSession;
import com.example.dynamodb.DynamoPersistenceService;
import com.example.openai.OpenAIConversationService;
import com.example.service.InterviewService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class InterviewServiceImpl implements InterviewService {
    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;
    @Autowired
    private OpenAIConversationService openAIConversationService;

    @Override
    public InterviewSession startInterview(String candidateId) {
        String sessionId = UUID.randomUUID().toString();
        String conversationId = openAIConversationService.createConversation();
        InterviewSession session = new InterviewSession();
        session.setSessionId(sessionId);
        session.setConversationId(conversationId);
        session.setCandidateId(candidateId);
        session.setStatus("ACTIVE");
        session.setCreatedAt(Instant.now());
        // Set TTL for 7 days
        session.setTtl(Instant.now().plusSeconds(7 * 24 * 3600).getEpochSecond());
        dynamoPersistenceService.saveSession(session);
        return session;
    }

    @Override
    public void completeInterview(String sessionId) {
        // Implementation for interview completion
    }
}
