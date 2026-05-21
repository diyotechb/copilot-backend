package com.example.livetranscription.dynamodb;

import com.example.livetranscription.model.InterviewSession;
import com.example.livetranscription.model.InterviewMessage;

import java.util.List;

public interface DynamoPersistenceService {
    void saveSession(InterviewSession session);
    InterviewSession getSession(String sessionId);
    void saveMessage(InterviewMessage message);
    List<InterviewMessage> getMessages(String sessionId);
    void saveInterviewSummary(String sessionId, String summaryJson);
}
