package com.example.openai;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

@Service
public class InterviewOpenAiConversationServiceAdapter implements OpenAIConversationService {
    @Autowired
    private InterviewOpenAiConversationService interviewService;

    @Override
    public String createConversation() {
        return interviewService.createConversation();
    }

    @Override
    public void injectSystemInstructions(String conversationId, String instructions) {
        interviewService.injectSystemInstructions(instructions);
    }

    @Override
    public void injectResumeSummary(String conversationId, String summary) {
        interviewService.injectResumeSummary(summary);
    }

    @Override
    public void appendUserMessage(String conversationId, String message) {
        interviewService.appendUserMessage(message);
    }

    @Override
    public void streamAssistantResponse(String conversationId, Consumer<String> tokenConsumer) {
        interviewService.streamAssistantResponse(tokenConsumer);
    }
}
