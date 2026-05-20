package com.example.dynamodb;

import com.example.model.InterviewSession;
import com.example.model.InterviewMessage;
import java.util.List;

public interface DynamoPersistenceService {
    void saveSession(InterviewSession session);

    InterviewSession getSession(String sessionId);

    void saveMessage(InterviewMessage message);

    List<InterviewMessage> getMessages(String sessionId);

    void saveInterviewSummary(String sessionId, String summaryJson);
}
