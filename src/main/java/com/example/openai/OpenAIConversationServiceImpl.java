package com.example.openai;

import org.springframework.stereotype.Service;
import java.util.function.Consumer;

@Service
public class OpenAIConversationServiceImpl implements OpenAIConversationService {
    @Override
    public String createConversation() {
        // TODO: Call OpenAI Responses API to create a conversation/thread
        return "openai-conv-id";
    }

    @Override
    public void injectSystemInstructions(String conversationId, String instructions) {
        // TODO: Inject system instructions as a system message
    }

    @Override
    public void injectResumeSummary(String conversationId, String summary) {
        // TODO: Inject resume summary as a system message
    }

    @Override
    public void appendUserMessage(String conversationId, String message) {
        // TODO: Append user message to OpenAI conversation
    }

    @Override
    public void streamAssistantResponse(String conversationId, Consumer<String> tokenConsumer) {
        // TODO: Stream OpenAI assistant response token-by-token
    }
}
