package com.example.livetranscription.liveassist;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public final class SystemPromptProvider {

    private static final Logger log = LoggerFactory.getLogger(SystemPromptProvider.class);
    private static final String RESOURCE = "systeminstruction.txt";

    private static volatile String cached;

    private SystemPromptProvider() {}

    public static String load(String fallback) {
        String c = cached;
        if (c != null) return c;
        synchronized (SystemPromptProvider.class) {
            if (cached != null) return cached;
            String loaded = readResource();
            if (loaded != null && !loaded.isBlank()) {
                log.info("Loaded system prompt from classpath:{} ({} chars)", RESOURCE, loaded.length());
                cached = loaded;
            } else {
                log.warn("System prompt {} missing or empty — using built-in fallback", RESOURCE);
                cached = fallback;
            }
            return cached;
        }
    }

    private static String readResource() {
        try {
            ClassPathResource res = new ClassPathResource(RESOURCE);
            if (!res.exists()) return null;
            try (InputStream in = res.getInputStream()) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("Failed to read system prompt {}", RESOURCE, e);
            return null;
        }
    }
}
