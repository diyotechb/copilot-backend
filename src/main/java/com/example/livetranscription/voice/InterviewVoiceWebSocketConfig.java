package com.example.livetranscription.voice;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Registers /ws/interview-voice — the live interview assistant WebSocket endpoint.
 * Path is outside /api/** so it inherits the SecurityConfig anyRequest().permitAll()
 * rule, matching the same open-access policy as /api/realtime-voice.
 * chrome-extension://* is included so the Chrome extension can connect without auth.
 */
@Configuration
public class InterviewVoiceWebSocketConfig implements WebSocketConfigurer {

    private final InterviewVoiceWebSocketHandler handler;
    private final String deployedOrigin;

    public InterviewVoiceWebSocketConfig(InterviewVoiceWebSocketHandler handler,
                                         @Value("${app.cors.deployed-origin:}") String deployedOrigin) {
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

        registry.addHandler(handler, "/ws/interview-voice")
                .setAllowedOriginPatterns(origins.toArray(new String[0]));
    }
}
