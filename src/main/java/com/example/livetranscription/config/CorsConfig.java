package com.example.livetranscription.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Configuration
public class CorsConfig {

    @Value("${app.cors.deployed-origin:}")
    private String deployedOrigin;

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> patterns = new ArrayList<>(Arrays.asList(BackendDefaults.CORS_LOCALHOST_PATTERNS));
        if (deployedOrigin != null && !deployedOrigin.isBlank()) {
            for (String o : deployedOrigin.split("\\s*,\\s*")) {
                if (!o.isBlank()) patterns.add(o.trim());
            }
        }

        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(patterns);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Content-Type", "Content-Disposition"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }
}
