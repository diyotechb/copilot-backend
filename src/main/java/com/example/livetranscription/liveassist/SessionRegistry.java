package com.example.livetranscription.liveassist;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class SessionRegistry {

    private final Map<String, Set<WebSocketSession>> byConversation = new ConcurrentHashMap<>();

    public void register(String conversationId, WebSocketSession session) {
        byConversation.computeIfAbsent(conversationId, k -> ConcurrentHashMap.newKeySet()).add(session);
    }

    public void unregister(String conversationId, WebSocketSession session) {
        Set<WebSocketSession> set = byConversation.get(conversationId);
        if (set == null) return;
        set.remove(session);
        if (set.isEmpty()) byConversation.remove(conversationId);
    }

    public Set<WebSocketSession> sessionsFor(String conversationId) {
        Set<WebSocketSession> set = byConversation.get(conversationId);
        return set == null ? Set.of() : set;
    }

    /** Number of conversations with at least one live WebSocket connection. */
    public int liveSessionCount() {
        return byConversation.size();
    }

    public Set<String> liveConversationIds() {
        return new java.util.HashSet<>(byConversation.keySet());
    }
}
