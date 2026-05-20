package com.example.openai;

import com.example.livetranscription.service.openai.OpenAiChatService;
import com.example.livetranscription.service.openai.OpenAiChatService.Message;
import com.example.livetranscription.voice.VoiceOpenAiStreamService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

@Service
public class InterviewOpenAiConversationService {
    @Autowired
    private OpenAiChatService chatService;
    @Autowired
    private VoiceOpenAiStreamService streamService;

    // In-memory conversation store for demonstration (replace with DynamoDB in
    // prod)
    private final List<Message> conversation = new ArrayList<>();

    public String createConversation() {
        conversation.clear();
        return "interview-conv-id";
    }

    public void injectSystemInstructions(String instructions) {
        conversation.add(new Message("system", instructions));
    }

    public void injectResumeSummary(String summary) {
        conversation.add(new Message("system", summary));
    }

    public void appendUserMessage(String message) {
        conversation.add(new Message("user", message));
    }

    public void appendAssistantMessage(String message) {
        conversation.add(new Message("assistant", message));
    }

    public void streamAssistantResponse(Consumer<String> tokenConsumer) {
        Flux<String> flux = streamService.stream(new ArrayList<>(conversation));
        flux.subscribe(tokenConsumer);
    }

    public String getAssistantResponse() {
        // Non-streaming fallback
        String result = chatService.chat(new OpenAiChatService.ChatRequest(
                "gpt-4o", new ArrayList<>(conversation), null, false));
        appendAssistantMessage(result);
        return result;
    }

    public List<Message> getConversation() {
        return new ArrayList<>(conversation);
    }
}
