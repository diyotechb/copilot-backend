package com.example.livetranscription.dynamodb;

import com.example.livetranscription.model.LiveAssistMessage;

import java.util.List;

public interface DynamoPersistenceService {
    void saveMessage(LiveAssistMessage message);
    List<LiveAssistMessage> getMessages(String sessionId);
    void deleteMessage(String sessionId, java.time.Instant timestamp);
}
