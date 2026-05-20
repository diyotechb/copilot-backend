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
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;

public class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);

    private static final long MAX_BUCKETS = 5_000L;
    private static final Duration BUCKET_IDLE_TTL = Duration.ofHours(1);

    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(MAX_BUCKETS)
            .expireAfterAccess(BUCKET_IDLE_TTL)
            .build();

    private enum Route {
        GENERATE("generate"),
        ANALYZE("analyze"),
        TRANSCRIBE("transcribe"),
        DEFAULT("default");
        final String key;
        Route(String key) { this.key = key; }
    }

    private record Limit(int capacity, int refillTokens, Duration refillInterval) {}

    private static final Limit ADMIN_GENERATE   = new Limit(12, 12, Duration.ofHours(1));
    private static final Limit ADMIN_ANALYZE    = new Limit(12, 12, Duration.ofHours(1));
    private static final Limit ADMIN_TRANSCRIBE = new Limit(400, 7, Duration.ofSeconds(60));

    private static final Limit USER_GENERATE    = new Limit(2,  2,  Duration.ofHours(1));
    private static final Limit USER_ANALYZE     = new Limit(2,  2,  Duration.ofHours(1));
    private static final Limit USER_TRANSCRIBE  = new Limit(40, 40, Duration.ofHours(1));

    private static final Limit DEFAULT_LIMIT = new Limit(
            BackendDefaults.RATE_LIMIT_CAPACITY,
            BackendDefaults.RATE_LIMIT_REFILL_TOKENS,
            Duration.ofSeconds(BackendDefaults.RATE_LIMIT_REFILL_SECONDS));

    private static Route classify(String path) {
        if (path == null) return Route.DEFAULT;
        if (path.equals("/api/interview/generate"))  return Route.GENERATE;
        if (path.equals("/api/interview/analyze"))   return Route.ANALYZE;
        if (path.equals("/api/transcribe/audio"))    return Route.TRANSCRIBE;
        return Route.DEFAULT;
    }

    private static Limit pickLimit(Route route, boolean admin) {
        return switch (route) {
            case GENERATE   -> admin ? ADMIN_GENERATE   : USER_GENERATE;
            case ANALYZE    -> admin ? ADMIN_ANALYZE    : USER_ANALYZE;
            case TRANSCRIBE -> admin ? ADMIN_TRANSCRIBE : USER_TRANSCRIBE;
            default         -> DEFAULT_LIMIT;
        };
    }

    private static boolean isAdmin(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equals(r) || "ROLE_SUPER_ADMIN".equals(r)) return true;
        }
        return false;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        boolean admin = isAdmin(auth);
        Route route = classify(request.getRequestURI());
        Limit limit = pickLimit(route, admin);

        String bucketKey = clientKey(request, auth) + "|" + route.key + "|" + (admin ? "a" : "u");
        Bucket bucket = buckets.get(bucketKey, k -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("rate_limit_exceeded client={} route={} admin={} retryAfterSec={}",
                    bucketKey, route.key, admin, retryAfterSec);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"category\":\"" + route.key
                            + "\",\"retryAfterSeconds\":" + retryAfterSec + "}");
        }
    }

    private static String clientKey(HttpServletRequest request, Authentication auth) {
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }

    private static Bucket newBucket(Limit limit) {
        Bandwidth bw = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillIntervally(limit.refillTokens(), limit.refillInterval())
                .build();
        return Bucket.builder().addLimit(bw).build();
    }

    /** Approximate number of (client|route|tier) buckets currently tracked. */
    public long bucketCount() {
        return buckets.estimatedSize();
    }
}
