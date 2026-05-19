package com.example.livetranscription.controller;

import com.example.livetranscription.config.RateLimitFilter;
import com.example.livetranscription.service.openai.TtsAudioCache;
import com.example.livetranscription.ws.RealtimeWebSocketHandler;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Read-only operations endpoints for staff (ADMIN / SUPER_ADMIN / DIYO_EMP).
 * The role guard lives in SecurityConfig — this controller assumes the
 * filter chain has already authorized the request.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final RealtimeWebSocketHandler wsHandler;
    private final InterviewGenerationController generationController;
    private final TtsAudioCache ttsCache;
    private final RateLimitFilter rateLimitFilter;

    public AdminController(RealtimeWebSocketHandler wsHandler,
                           InterviewGenerationController generationController,
                           TtsAudioCache ttsCache,
                           RateLimitFilter rateLimitFilter) {
        this.wsHandler = wsHandler;
        this.generationController = generationController;
        this.ttsCache = ttsCache;
        this.rateLimitFilter = rateLimitFilter;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);
        body.put("active_websocket_sessions", wsHandler.activeSessionCount());
        body.put("interview_executor", generationController.executorStats());
        body.put("tts_cache", ttsCacheSnapshot());
        body.put("rate_limit_buckets", rateLimitFilter.bucketCount());
        body.put("memory", memorySnapshot());

        // Always-fresh stats; no value caching this response.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private Map<String, Object> ttsCacheSnapshot() {
        CacheStats stats = ttsCache.stats();
        long total = stats.hitCount() + stats.missCount();
        double hitRate = total == 0 ? 0.0 : (double) stats.hitCount() / (double) total;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", ttsCache.estimatedSize());
        out.put("hit_count", stats.hitCount());
        out.put("miss_count", stats.missCount());
        out.put("hit_rate", hitRate);
        out.put("eviction_count", stats.evictionCount());
        out.put("chars_saved", ttsCache.charsSaved());
        out.put("estimated_savings_usd", ttsCache.estimatedSavingsUsd());
        return out;
    }

    private Map<String, Object> memorySnapshot() {
        Runtime r = Runtime.getRuntime();
        long max = r.maxMemory();
        long total = r.totalMemory();
        long free = r.freeMemory();
        long used = total - free;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("used_bytes", used);
        out.put("total_bytes", total);
        out.put("max_bytes", max);
        out.put("used_mb", used / (1024L * 1024L));
        out.put("max_mb", max / (1024L * 1024L));
        out.put("used_fraction", max == 0 ? 0.0 : (double) used / (double) max);
        return out;
    }
}
