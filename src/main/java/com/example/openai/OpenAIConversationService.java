package com.example.openai;

public interface OpenAIConversationService {
    String createConversation();

    void injectSystemInstructions(String conversationId, String instructions);

    void injectResumeSummary(String conversationId, String summary);

    void appendUserMessage(String conversationId, String message);

    void streamAssistantResponse(String conversationId, java.util.function.Consumer<String> tokenConsumer);
}
