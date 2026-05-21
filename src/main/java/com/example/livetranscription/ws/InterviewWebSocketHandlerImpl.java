package com.example.livetranscription.ws;

import com.example.livetranscription.service.impl.InterviewWebSocketSessionService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class InterviewWebSocketHandlerImpl extends TextWebSocketHandler {

    @Autowired
    private InterviewWebSocketSessionService sessionService;

    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            JsonNode node = mapper.readTree(message.getPayload());
            String sessionId = node.get("sessionId").asText();
            String userMessage = node.get("message").asText();
            sessionService.handleCandidateMessage(sessionId, userMessage, token -> {
                try {
                    session.sendMessage(new TextMessage(token));
                } catch (Exception e) {
                    // swallow send errors on closed sessions
                }
            });
        } catch (Exception e) {
            // swallow parse errors
        }
    }
}
