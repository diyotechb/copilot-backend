package com.example.config;

import com.example.websocket.InterviewWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final InterviewWebSocketHandler interviewWebSocketHandler;

    public WebSocketConfig(InterviewWebSocketHandler interviewWebSocketHandler) {
        this.interviewWebSocketHandler = interviewWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(interviewWebSocketHandler, "/ws/interview").setAllowedOrigins("*");
    }
}
