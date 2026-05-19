package com.example.livetranscription.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    // Bounded so a flood of distinct keys (e.g. spoofed X-Forwarded-For) cannot
    // grow the map without limit and OOM a small instance. Sized for t3.micro.
    private static final long MAX_BUCKETS = 5_000L;
    private static final Duration BUCKET_IDLE_TTL = Duration.ofHours(1);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS)
            .expireAfterAccess(BUCKET_IDLE_TTL)
            .build();

    // Per-endpoint buckets. A buggy retry loop or attacker hammering one
    // expensive route can no longer burn the OpenAI / AssemblyAI budget
    // by sharing the cheap-endpoint budget. Each category gets its own
    // bucket per client, so saturating GENERATE doesn't 429 TTS playback.
    private enum RouteCategory {
        // /api/interview/generate — 18 OpenAI calls per request, by far the
        // most expensive endpoint. Cap at 10 per hour per user (~$0.30/hr
        // worst case wasted spend if buggy).
        GENERATE("generate", 10, 10, Duration.ofHours(1)),

        // /api/interview/analyze — 1 large OpenAI call (up to ~30K input
        // tokens). Cap at 30/hour: enough for legitimate re-runs and partial
        // refills, low enough to bound runaway retries.
        ANALYZE("analyze", 30, 30, Duration.ofHours(1)),

        // /api/transcribe/audio — AssemblyAI pre-recorded upload. Eats free
        // tier hours fast. 30/hour matches an active power user without
        // letting a retry loop drain the 185h/month cap in a single afternoon.
        TRANSCRIBE("transcribe", 30, 30, Duration.ofHours(1)),

        // Everything else — TTS (cached server-side), voices list, sample
        // content, health, etc. Keeps the original global default.
        DEFAULT("default",
                BackendDefaults.RATE_LIMIT_CAPACITY,
                BackendDefaults.RATE_LIMIT_REFILL_TOKENS,
                Duration.ofSeconds(BackendDefaults.RATE_LIMIT_REFILL_SECONDS));

        final String key;
        final int capacity;
        final int refillTokens;
        final Duration refillInterval;

        RouteCategory(String key, int capacity, int refillTokens, Duration refillInterval) {
            this.key = key;
            this.capacity = capacity;
            this.refillTokens = refillTokens;
            this.refillInterval = refillInterval;
        }
    }

    private static RouteCategory classify(String path) {
        if (path == null) return RouteCategory.DEFAULT;
        if (path.equals("/api/interview/generate")) return RouteCategory.GENERATE;
        if (path.equals("/api/interview/analyze")) return RouteCategory.ANALYZE;
        if (path.equals("/api/transcribe/audio")) return RouteCategory.TRANSCRIBE;
        return RouteCategory.DEFAULT;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        RouteCategory category = classify(request.getRequestURI());
        String bucketKey = clientKey(request) + "|" + category.key;
        Bucket bucket = buckets.get(bucketKey, k -> newBucket(category));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("rate_limit_exceeded client={} category={} path={} retryAfterSec={}",
                    bucketKey, category.key, request.getRequestURI(), retryAfterSec);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"category\":\"" + category.key
                            + "\",\"retryAfterSeconds\":" + retryAfterSec + "}");
        }
    }

    private static String clientKey(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }

    private static Bucket newBucket(RouteCategory category) {
        Bandwidth limit = Bandwidth.builder()
                .capacity(category.capacity)
                .refillIntervally(category.refillTokens, category.refillInterval)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    /** Approximate number of (client|category) buckets currently tracked. */
    public long bucketCount() {
        return buckets.estimatedSize();
    }
}
