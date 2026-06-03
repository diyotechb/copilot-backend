package com.example.livetranscription.liveassist;

import com.example.livetranscription.service.openai.OpenAiChatService.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class ConversationContext {
    private static final int MAX_MESSAGES = 20;
    private static final String SYSTEM_PROMPT =
            "You are a helpful voice copilot. Keep responses short, natural, and conversational.";

    private final String conversationId;
    private final Deque<Message> messages = new ArrayDeque<>();

    public ConversationContext(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getId() {
        return conversationId;
    }

    public synchronized List<Message> buildMessages(String userText) {
        List<Message> out = new ArrayList<>(messages.size() + 2);
        out.add(new Message("system", SYSTEM_PROMPT));
        out.addAll(messages);
        out.add(new Message("user", userText));
        return out;
    }

    public synchronized void recordExchange(String userText, String assistantText) {
        messages.addLast(new Message("user", userText));
        messages.addLast(new Message("assistant", assistantText));
        while (messages.size() > MAX_MESSAGES) {
            messages.removeFirst();
        }
    }
}
