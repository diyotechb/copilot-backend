package com.example.livetranscription.config;

import com.example.livetranscription.ws.RealtimeV2WebSocketHandler;
import com.example.livetranscription.ws.RealtimeWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final RealtimeV2WebSocketHandler v2Handler;

    public WebSocketConfig(RealtimeWebSocketHandler handler, RealtimeV2WebSocketHandler v2Handler) {
        this.handler = handler;
        this.v2Handler = v2Handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/realtime").setAllowedOrigins("*");
        registry.addHandler(v2Handler, "/realtime-v2").setAllowedOrigins("*");
    }
}
