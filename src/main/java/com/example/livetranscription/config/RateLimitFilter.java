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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        TRANSCRIBE_CLAIM("transcribe_claim"),
        INTERVIEWS("interviews"),
        TTS("tts"),
        DEFAULT("default");
        final String key;
        Route(String key) { this.key = key; }
    }

    // 1 interview = 1 generate + up to 35 transcribe + 1 analyze; analysis is staff-only.
    private enum Tier { STAFF, COPILOT, BASE }

    private record Limit(int capacity, int refillTokens, Duration refillInterval) {}

    private static final Limit STAFF_GENERATE   = new Limit(22, 22, Duration.ofHours(1));
    private static final Limit STAFF_ANALYZE    = new Limit(90, 90, Duration.ofHours(1));
    private static final Limit STAFF_TRANSCRIBE = new Limit(720, 12, Duration.ofSeconds(60));

    private static final Limit COPILOT_GENERATE = new Limit(12, 12, Duration.ofHours(1));

    private static final Limit BASE_GENERATE   = new Limit(2,  2,  Duration.ofHours(1));
    private static final Limit BASE_ANALYZE    = new Limit(2,  2,  Duration.ofHours(1));
    private static final Limit BASE_TRANSCRIBE = new Limit(40, 40, Duration.ofHours(1));

    private static final Limit CANDIDATE_GENERATE = new Limit(10, 10, Duration.ofDays(1));
    private static final Limit INTERVIEWS_LIMIT   = new Limit(300, 300, Duration.ofHours(1));

    private static final Limit CANDIDATE_TRANSCRIBE       = new Limit(50, 50, Duration.ofDays(1));
    private static final Limit CANDIDATE_TRANSCRIBE_CLAIM = new Limit(1, 1, Duration.ofDays(1));

    private static final Limit TTS_LIMIT = new Limit(120, 120, Duration.ofMinutes(1));

    private static final Limit DEFAULT_LIMIT = new Limit(
            BackendDefaults.RATE_LIMIT_CAPACITY,
            BackendDefaults.RATE_LIMIT_REFILL_TOKENS,
            Duration.ofSeconds(BackendDefaults.RATE_LIMIT_REFILL_SECONDS));

    private static Route classify(String path) {
        if (path == null) return Route.DEFAULT;
        if (path.equals("/api/interview/generate"))  return Route.GENERATE;
        if (path.equals("/api/interview/analyze"))   return Route.ANALYZE;
        if (path.equals("/api/transcribe/audio"))    return Route.TRANSCRIBE;
        if (path.equals("/api/interviews/transcribe-claim")) return Route.TRANSCRIBE_CLAIM;
        if (path.startsWith("/api/interviews/"))     return Route.INTERVIEWS;
        if (path.equals("/api/tts/speech"))          return Route.TTS;
        return Route.DEFAULT;
    }

    private static Limit pickLimit(Route route, Tier tier) {
        return switch (route) {
            case GENERATE   -> switch (tier) { case STAFF -> STAFF_GENERATE; case COPILOT -> COPILOT_GENERATE; default -> BASE_GENERATE; };
            case ANALYZE    -> tier == Tier.STAFF ? STAFF_ANALYZE    : BASE_ANALYZE;
            case TRANSCRIBE -> tier == Tier.STAFF ? STAFF_TRANSCRIBE : BASE_TRANSCRIBE;
            case TRANSCRIBE_CLAIM -> DEFAULT_LIMIT;
            case INTERVIEWS -> INTERVIEWS_LIMIT;
            case TTS        -> TTS_LIMIT;
            default         -> DEFAULT_LIMIT;
        };
    }

    private static Limit candidateLimit(Route route, Tier tier) {
        return switch (route) {
            case GENERATE        -> CANDIDATE_GENERATE;
            case TRANSCRIBE      -> CANDIDATE_TRANSCRIBE;
            case TRANSCRIBE_CLAIM -> CANDIDATE_TRANSCRIBE_CLAIM;
            default              -> pickLimit(route, tier);
        };
    }

    private static Tier tierFor(Authentication auth) {
        if (auth == null || !auth.isAuthenticated()) return Tier.BASE;
        boolean copilot = false;
        for (GrantedAuthority a : auth.getAuthorities()) {
            String r = a.getAuthority();
            if ("ROLE_ADMIN".equals(r) || "ROLE_SUPER_ADMIN".equals(r) || "ROLE_DIYO_EMP".equals(r)) return Tier.STAFF;
            if ("ROLE_COPILOT_USER".equals(r)) copilot = true;
        }
        return copilot ? Tier.COPILOT : Tier.BASE;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Tier tier = tierFor(auth);
        Route route = classify(request.getRequestURI());
        boolean hasEnrollment = enrollmentHeader(request) != null;
        Limit limit = hasEnrollment ? candidateLimit(route, tier) : pickLimit(route, tier);

        String bucketKey = clientKey(request, auth) + "|" + route.key + "|" + tier;
        Bucket bucket = buckets.get(bucketKey, k -> newBucket(limit));
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", String.valueOf(probe.getRemainingTokens()));
            chain.doFilter(request, response);
        } else {
            long retryAfterSec = Math.max(1, probe.getNanosToWaitForRefill() / 1_000_000_000L);
            log.warn("rate_limit_exceeded client={} route={} tier={} retryAfterSec={}",
                    bucketKey, route.key, tier, retryAfterSec);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf(retryAfterSec));
            response.setContentType("application/json");
            response.getWriter().write(
                    "{\"error\":\"rate_limit_exceeded\",\"category\":\"" + route.key
                            + "\",\"retryAfterSeconds\":" + retryAfterSec + "}");
        }
    }

    private static String clientKey(HttpServletRequest request, Authentication auth) {
        String enrollmentId = enrollmentHeader(request);
        if (enrollmentId != null) {
            return "cand:" + enrollmentId;
        }
        if (auth != null && auth.isAuthenticated() && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return "user:" + auth.getName();
        }
        String xff = request.getHeader("X-Forwarded-For");
        String ip = (xff != null && !xff.isBlank()) ? xff.split(",")[0].trim() : request.getRemoteAddr();
        return "ip:" + ip;
    }

    private static String enrollmentHeader(HttpServletRequest request) {
        String eid = request.getHeader("X-Enrollment-Id");
        if (eid == null) return null;
        eid = eid.trim();
        if (eid.isEmpty()) return null;
        return eid.length() > 64 ? eid.substring(0, 64) : eid;
    }

    private static Bucket newBucket(Limit limit) {
        Bandwidth bw = Bandwidth.builder()
                .capacity(limit.capacity())
                .refillIntervally(limit.refillTokens(), limit.refillInterval())
                .build();
        return Bucket.builder().addLimit(bw).build();
    }

    public long bucketCount() {
        return buckets.estimatedSize();
    }

    public boolean candidateHasDailyTranscribe(HttpServletRequest request) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Tier tier = tierFor(auth);
        String key = clientKey(request, auth) + "|" + Route.TRANSCRIBE_CLAIM.key + "|" + tier;
        Bucket bucket = buckets.getIfPresent(key);
        return bucket == null || bucket.getAvailableTokens() >= 1;
    }

    public static List<Map<String, Object>> limitsConfig() {
        List<Map<String, Object>> out = new ArrayList<>();
        out.add(limitRow("Start interview (generate)", "Candidate", CANDIDATE_GENERATE));
        out.add(limitRow("Start interview (generate)", "Copilot user", COPILOT_GENERATE));
        out.add(limitRow("Start interview (generate)", "Staff", STAFF_GENERATE));
        out.add(limitRow("Start interview (generate)", "Other user", BASE_GENERATE));
        out.add(limitRow("Interview save/sync", "Everyone", INTERVIEWS_LIMIT));
        out.add(limitRow("Detailed analysis", "Staff", STAFF_ANALYZE));
        out.add(limitRow("Transcribe answers", "Staff", STAFF_TRANSCRIBE));
        out.add(limitRow("Transcribe practice", "Candidate", CANDIDATE_TRANSCRIBE_CLAIM));
        out.add(limitRow("Voice playback (TTS)", "Everyone", TTS_LIMIT));
        out.add(limitRow("Other API calls", "Everyone", DEFAULT_LIMIT));
        return out;
    }

    private static Map<String, Object> limitRow(String action, String scope, Limit limit) {
        String window = humanInterval(limit.refillInterval());
        boolean burst = limit.refillTokens() != limit.capacity();
        String limitText = burst
                ? limit.refillTokens() + " / " + window + " (burst " + limit.capacity() + ")"
                : limit.capacity() + " / " + window;
        String per = perPhrase(window);
        String plain = burst
                ? "Up to " + limit.capacity() + " " + nounFor(action, limit.capacity()) + " in a row instantly, then it slows to "
                  + limit.refillTokens() + " per " + per + " under heavy continuous use — normal use never hits it."
                : "Up to " + limit.capacity() + " " + nounFor(action, limit.capacity()) + " per " + per + ", for each person.";
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("action", action);
        m.put("scope", scope);
        m.put("limit", limitText);
        m.put("plain", plain);
        return m;
    }

    private static String perPhrase(String window) {
        String w = window.startsWith("1 ") ? window.substring(2) : window;
        if (w.equals("min")) return "minute";
        if (w.equals("mins")) return "minutes";
        return w;
    }

    private static String nounFor(String action, int count) {
        String a = action == null ? "" : action.toLowerCase();
        boolean one = count == 1;
        if (a.contains("start interview") || a.contains("generate")) return one ? "interview" : "interviews";
        if (a.contains("save")) return one ? "save" : "saves";
        if (a.contains("analysis")) return one ? "detailed analysis" : "detailed analyses";
        if (a.contains("transcribe")) return one ? "transcription" : "transcriptions";
        if (a.contains("voice") || a.contains("tts")) return one ? "voice clip" : "voice clips";
        return one ? "API call" : "API calls";
    }

    private static String humanInterval(Duration d) {
        long secs = d.getSeconds();
        if (secs % 86400 == 0) return (secs / 86400) + (secs == 86400 ? " day" : " days");
        if (secs % 3600 == 0) return (secs / 3600) + (secs == 3600 ? " hour" : " hours");
        if (secs % 60 == 0) return (secs / 60) + (secs == 60 ? " min" : " mins");
        return secs + " sec";
    }

    public List<Map<String, Object>> snapshot() {
        List<Map<String, Object>> out = new ArrayList<>();
        buckets.asMap().forEach((key, bucket) -> {
            String[] parts = key.split("\\|", 3);
            if (parts.length < 3) return;
            int capacity = capacityFor(parts[0], parts[1], parts[2]);
            long available = bucket.getAvailableTokens();
            int colon = parts[0].indexOf(':');
            String prefix = colon > 0 ? parts[0].substring(0, colon) : "user";
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("clientType", clientTypeLabel(prefix));
            m.put("clientId", colon > 0 ? parts[0].substring(colon + 1) : parts[0]);
            m.put("route", routeLabel(parts[1]));
            m.put("tier", parts[2]);
            m.put("capacity", capacity);
            m.put("available", available);
            m.put("used", Math.max(0, capacity - available));
            out.add(m);
        });
        out.sort(Comparator.comparingLong(m -> -((Number) m.get("used")).longValue()));
        return out;
    }

    private static String clientTypeLabel(String prefix) {
        return switch (prefix) {
            case "cand" -> "Candidate";
            case "ip"   -> "Device";
            default     -> "User";
        };
    }

    private static String routeLabel(String routeKey) {
        return switch (routeKey) {
            case "generate"   -> "Start interview";
            case "analyze"    -> "Detailed analysis";
            case "transcribe" -> "Transcribe";
            case "transcribe_claim" -> "Transcribe (daily)";
            case "interviews" -> "Interview save";
            case "tts"        -> "Voice (TTS)";
            default           -> "Other API";
        };
    }

    private static int capacityFor(String client, String routeKey, String tierName) {
        Route route = Route.DEFAULT;
        for (Route r : Route.values()) {
            if (r.key.equals(routeKey)) { route = r; break; }
        }
        if (client.startsWith("cand:")) {
            if (route == Route.GENERATE)        return CANDIDATE_GENERATE.capacity();
            if (route == Route.TRANSCRIBE)      return CANDIDATE_TRANSCRIBE.capacity();
            if (route == Route.TRANSCRIBE_CLAIM) return CANDIDATE_TRANSCRIBE_CLAIM.capacity();
        }
        Tier tier;
        try { tier = Tier.valueOf(tierName); } catch (IllegalArgumentException e) { tier = Tier.BASE; }
        return pickLimit(route, tier).capacity();
    }
}
