package com.example.livetranscription.voice;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class ConversationContextStore {

    private final Cache<String, ConversationContext> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(30))
            .maximumSize(500)
            .build();

    public ConversationContext getOrCreate(String conversationId) {
        return cache.get(conversationId, ConversationContext::new);
    }
}
