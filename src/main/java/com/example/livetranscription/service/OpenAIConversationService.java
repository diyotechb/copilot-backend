package com.example.livetranscription.service;

import java.util.function.Consumer;

public interface OpenAIConversationService {
    String createConversation();
    void injectSystemInstructions(String conversationId, String instructions);
    void injectResumeSummary(String conversationId, String summary);
    void appendUserMessage(String conversationId, String message);
    void streamAssistantResponse(String conversationId, Consumer<String> tokenConsumer);
}
