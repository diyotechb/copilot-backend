package com.example.livetranscription.voice;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class InterviewContextStore {

    // 1-hour TTL matches the stated session duration requirement.
    // expireAfterAccess resets on each audio chunk, so an active interview
    // won't expire mid-session; an abandoned one cleans up after 1 hour of silence.
    private final Cache<String, InterviewConversationContext> cache = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofHours(1))
            .maximumSize(100)
            .build();

    public InterviewConversationContext getOrCreate(String conversationId) {
        return cache.get(conversationId, InterviewConversationContext::new);
    }
}
