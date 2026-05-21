package com.example.livetranscription.config;

import com.example.livetranscription.ws.RealtimeWebSocketHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeWebSocketHandler handler;
    private final String deployedOrigin;

    public WebSocketConfig(RealtimeWebSocketHandler handler,
                           @Value("${app.cors.deployed-origin}") String deployedOrigin) {
        this.handler = handler;
        this.deployedOrigin = deployedOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> patterns = new ArrayList<>(Arrays.asList(BackendDefaults.CORS_LOCALHOST_PATTERNS));
        if (deployedOrigin != null && !deployedOrigin.isBlank()) {
            for (String o : deployedOrigin.split("\\s*,\\s*")) {
                if (!o.isBlank()) patterns.add(o.trim());
            }
        }
        registry.addHandler(handler, "/api/realtime-transcribe")
                .setAllowedOriginPatterns(patterns.toArray(new String[0]));
    }
}
