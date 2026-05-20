package com.example.dynamodb;

import com.example.model.InterviewSession;
import com.example.model.InterviewMessage;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class DynamoPersistenceServiceImpl implements DynamoPersistenceService {
    @Override
    public void saveSession(InterviewSession session) {
        // TODO: Implement DynamoDB save logic
    }

    @Override
    public InterviewSession getSession(String sessionId) {
        // TODO: Implement DynamoDB get logic
        return null;
    }

    @Override
    public void saveMessage(InterviewMessage message) {
        // TODO: Implement DynamoDB save logic
    }

    @Override
    public List<InterviewMessage> getMessages(String sessionId) {
        // TODO: Implement DynamoDB query logic
        return null;
    }

    @Override
    public void saveInterviewSummary(String sessionId, String summaryJson) {
        // TODO: Implement DynamoDB update logic
    }
}
