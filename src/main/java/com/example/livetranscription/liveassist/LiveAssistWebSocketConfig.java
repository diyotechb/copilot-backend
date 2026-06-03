package com.example.livetranscription.liveassist;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class LiveAssistWebSocketConfig implements WebSocketConfigurer {

    private final LiveAssistWebSocketHandler handler;
    private final String deployedOrigin;

    public LiveAssistWebSocketConfig(LiveAssistWebSocketHandler handler,
                                         @Value("${app.cors.deployed-origin}") String deployedOrigin) {
        this.handler = handler;
        this.deployedOrigin = deployedOrigin;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        List<String> origins = new ArrayList<>(Arrays.asList(BackendDefaults.CORS_LOCALHOST_PATTERNS));
        origins.add("chrome-extension://*");

        if (deployedOrigin != null && !deployedOrigin.isBlank()) {
            for (String o : deployedOrigin.split("\\s*,\\s*")) {
                if (!o.isBlank()) origins.add(o.trim());
            }
        }

        registry.addHandler(handler, "/ws/live-assist")
                .setAllowedOriginPatterns(origins.toArray(new String[0]));
    }
}
