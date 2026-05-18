package com.example.livetranscription.config;

import com.example.livetranscription.ws.RealtimeV2WebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeV2WebSocketHandler v2Handler;

    public WebSocketConfig(RealtimeV2WebSocketHandler v2Handler) {
        this.v2Handler = v2Handler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(v2Handler, "/realtime-v2").setAllowedOrigins("*");
    }
}
