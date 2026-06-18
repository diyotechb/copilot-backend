package com.example.livetranscription.controller;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.config.RateLimitFilter;
import com.example.livetranscription.config.TranscriptionConfig;
import com.example.livetranscription.interviews.DailyQuestionBankStore;
import com.example.livetranscription.interviews.InterviewSession;
import com.example.livetranscription.interviews.InterviewSessionStore;
import com.example.livetranscription.liveassist.LiveAssistSession;
import com.example.livetranscription.liveassist.LiveAssistSessionStore;
import com.example.livetranscription.liveassist.SessionRegistry;
import com.example.livetranscription.service.openai.CachedQuestionService;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final InterviewSessionStore interviewSessionStore;
    private final SessionRegistry liveAssistRegistry;
    private final LiveAssistSessionStore liveAssistSessionStore;
    private final CachedQuestionService cachedQuestionService;

    public AdminController(RealtimeWebSocketHandler wsHandler,
                           InterviewGenerationController generationController,
                           TtsAudioCache ttsCache,
                           RateLimitFilter rateLimitFilter,
                           InterviewSessionStore interviewSessionStore,
                           SessionRegistry liveAssistRegistry,
                           LiveAssistSessionStore liveAssistSessionStore,
                           CachedQuestionService cachedQuestionService) {
        this.wsHandler = wsHandler;
        this.generationController = generationController;
        this.ttsCache = ttsCache;
        this.rateLimitFilter = rateLimitFilter;
        this.interviewSessionStore = interviewSessionStore;
        this.liveAssistRegistry = liveAssistRegistry;
        this.liveAssistSessionStore = liveAssistSessionStore;
        this.cachedQuestionService = cachedQuestionService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("uptime_seconds", ManagementFactory.getRuntimeMXBean().getUptime() / 1000L);
        body.put("active_websocket_sessions", wsHandler.activeSessionCount());
        body.put("active_interviews", activeInterviewsCount());
        body.put("live_assist_live", liveAssistRegistry.liveSessionCount());
        body.put("interview_executor", generationController.executorStats());
        body.put("session_max_hours", TranscriptionConfig.MAX_SESSION_DURATION_HOURS);
        body.put("tts_cache", ttsCacheSnapshot());
        body.put("question_cache", questionCacheSnapshot());
        body.put("rate_limit_buckets", rateLimitFilter.bucketCount());
        body.put("memory", memorySnapshot());

        // Always-fresh stats; no value caching this response.
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    @GetMapping("/details")
    public ResponseEntity<Map<String, Object>> details() {
        Map<String, Object> rateLimits = new LinkedHashMap<>();
        rateLimits.put("buckets", rateLimitFilter.snapshot());
        rateLimits.put("capabilities", RateLimitFilter.limitsConfig());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("rateLimits", rateLimits);
        body.put("interviews", activeInterviews());
        body.put("liveAssist", liveAssistLive());
        body.put("transcriptions", wsHandler.activeSessions());
        body.put("questionBanks", questionBanksToday());

        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body);
    }

    private volatile int cachedActiveInterviews = -1;
    private volatile long cachedActiveInterviewsAt = 0;

    private int activeInterviewsCount() {
        long now = System.currentTimeMillis();
        if (cachedActiveInterviews >= 0 && now - cachedActiveInterviewsAt < BackendDefaults.ADMIN_ACTIVE_COUNT_CACHE_MS) {
            return cachedActiveInterviews;
        }
        String since = Instant.now().minusSeconds(BackendDefaults.SESSION_ACTIVE_WINDOW_SECONDS).toString();
        int count = interviewSessionStore.countActiveSince(since);
        cachedActiveInterviews = count;
        cachedActiveInterviewsAt = now;
        return count;
    }

    private static boolean isFreshlyActive(InterviewSession s) {
        if (!"ACTIVE".equals(s.getStatus()) || s.getUpdatedAt() == null) return false;
        return s.getUpdatedAt().isAfter(Instant.now().minusSeconds(BackendDefaults.SESSION_ACTIVE_WINDOW_SECONDS));
    }

    private List<Map<String, Object>> activeInterviews() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (InterviewSession s : interviewSessionStore.listAll()) {
            if (!isFreshlyActive(s)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", firstNonBlank(s.getCandidateName(), s.getLabel(), "Interview"));
            m.put("difficulty", s.getDifficulty());
            m.put("startedAt", firstNonBlank(s.getStartedAt(), s.getCreatedAt() != null ? s.getCreatedAt().toString() : null));
            m.put("by", firstNonBlank(s.getCreatedByEmail(), s.getCreatedBy()));
            out.add(m);
        }
        return out;
    }

    private List<Map<String, Object>> liveAssistLive() {
        Set<String> liveIds = liveAssistRegistry.liveConversationIds();
        List<Map<String, Object>> out = new ArrayList<>();
        if (liveIds.isEmpty()) return out;
        Map<String, LiveAssistSession> byConversation = new LinkedHashMap<>();
        for (LiveAssistSession s : liveAssistSessionStore.listAll()) {
            if (s.getConversationId() != null) byConversation.put(s.getConversationId(), s);
        }
        for (String conversationId : liveIds) {
            LiveAssistSession s = byConversation.get(conversationId);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", s != null ? firstNonBlank(s.getCandidateName(), s.getLabel(), "Session") : conversationId);
            m.put("status", s != null ? s.getStatus() : "LIVE");
            m.put("by", s != null ? firstNonBlank(s.getCreatedByEmail(), null) : null);
            out.add(m);
        }
        return out;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
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

    private Map<String, Object> questionCacheSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("bank", cacheStatsMap(cachedQuestionService.bankCacheStats(), cachedQuestionService.bankCacheSize()));
        out.put("top_up", cacheStatsMap(cachedQuestionService.topUpCacheStats(), cachedQuestionService.topUpCacheSize()));
        out.put("banks_built_today", cachedQuestionService.banksBuiltToday());
        out.put("interviews_served_from_cache", cachedQuestionService.interviewsServed());
        out.put("fully_personalized_bypass", cachedQuestionService.fullyPersonalizedBypassCount());
        out.put("bank_builds", cachedQuestionService.bankBuilds());
        out.put("bank_build_calls", cachedQuestionService.bankBuildCalls());
        out.put("top_up_builds", cachedQuestionService.topUpBuilds());
        out.put("estimated_openai_calls_saved", cachedQuestionService.estimatedCallsSaved());
        out.put("estimated_savings_usd", cachedQuestionService.estimatedSavingsUsd());
        return out;
    }

    private static Map<String, Object> cacheStatsMap(CacheStats stats, long size) {
        long total = stats.hitCount() + stats.missCount();
        double hitRate = total == 0 ? 0.0 : (double) stats.hitCount() / (double) total;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("size", size);
        out.put("hit_count", stats.hitCount());
        out.put("miss_count", stats.missCount());
        out.put("hit_rate", hitRate);
        return out;
    }

    private List<Map<String, Object>> questionBanksToday() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (DailyQuestionBankStore.Bank b : cachedQuestionService.banksForToday()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("bankKey", b.bankKey);
            m.put("category", b.category);
            m.put("difficulty", b.difficulty);
            m.put("status", b.status);
            m.put("count", b.count);
            m.put("generatedAt", b.generatedAt);
            out.add(m);
        }
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
