package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.interviews.DailyQuestionBankStore;
import com.example.livetranscription.interviews.PersonalizedQuestionCacheStore;
import com.example.livetranscription.service.openai.InterviewGenerationService.BankPool;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerateRequest;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerationListener;
import com.example.livetranscription.service.openai.InterviewGenerationService.QA;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Serves interview questions from a shared daily cache instead of regenerating
 * from scratch per candidate. Two tiers:
 *   - a shared bank per {date|category|difficulty}, built once a day and reused by
 *     everyone of that type (held in {@link QuestionBankCache} + DynamoDB);
 *   - a small per-candidate top-up (~5 resume + ~5 keyword questions), cached per
 *     {date|hash(resume+keywords+type)}.
 * The two are merged and run through the existing {@link InterviewGenerationService}
 * assembly so the on-screen format is identical to a live generation.
 */
@Service
public class CachedQuestionService {

    private static final Logger log = LoggerFactory.getLogger(CachedQuestionService.class);

    // Typical OpenAI calls a full live generation would spend — used only to
    // estimate calls/dollars saved on the admin page.
    private static final int LIVE_GENERATION_EST_CALLS = 7;

    private static final Set<String> LEVELS = Set.of("Beginner", "Intermediate", "Advanced");
    private static final String DEFAULT_LEVEL = "Beginner";

    // Common words to ignore when scoring resume/keyword relevance.
    private static final Set<String> RANK_STOPWORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "have", "has", "had", "was", "were",
            "are", "but", "not", "you", "your", "our", "their", "they", "what", "when", "where",
            "which", "how", "who", "into", "over", "under", "about", "using", "used", "use", "work",
            "worked", "working", "team", "project", "projects", "experience", "years", "year");

    private final InterviewGenerationService generationService;
    private final QuestionBankCache bankCache;
    private final DailyQuestionBankStore bankStore;
    private final PersonalizedQuestionCacheStore topUpStore;
    private final ObjectMapper mapper;

    private final Cache<String, List<QA>> topUpCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(12))
            .maximumSize(2_000)
            .recordStats()
            .build();

    private final AtomicLong interviewsServed = new AtomicLong();
    private final AtomicLong bankBuilds = new AtomicLong();
    private final AtomicLong bankBuildCalls = new AtomicLong();
    private final AtomicLong topUpBuilds = new AtomicLong();
    private final AtomicLong fullyPersonalizedBypass = new AtomicLong();

    public CachedQuestionService(InterviewGenerationService generationService,
                                 QuestionBankCache bankCache,
                                 DailyQuestionBankStore bankStore,
                                 PersonalizedQuestionCacheStore topUpStore,
                                 ObjectMapper mapper) {
        this.generationService = generationService;
        this.bankCache = bankCache;
        this.bankStore = bankStore;
        this.topUpStore = topUpStore;
        this.mapper = mapper;
    }

    /** Cached-mode generation. Emits the same SSE events as a live generation. */
    public void generate(GenerateRequest req, GenerationListener listener) {
        if (req == null || req.resumeText() == null || req.resumeText().isBlank()) {
            listener.onError(new IllegalArgumentException("resumeText is required"));
            return;
        }
        List<QA> assembled;
        try {
            assembled = serve(req);
        } catch (Throwable t) {
            // Cache/store failure (e.g. tables not yet provisioned). Nothing has been
            // emitted yet, so degrade gracefully to a normal live generation.
            log.warn("cached_question_serve_failed, falling back to live generation error={}", t.getMessage());
            generationService.generate(req, listener);
            return;
        }
        int mainCount = (int) assembled.stream()
                .filter(q -> q.type() == null || q.type().isEmpty() || "main".equals(q.type()))
                .count();
        listener.onProgress(mainCount, mainCount, true);
        listener.onUpdate(assembled);
        listener.onDone(assembled);
        interviewsServed.incrementAndGet();
    }

    public List<QA> serve(GenerateRequest req) {
        String level = resolveLevel(req.difficulty());
        String date = today();
        String category = (req.category() == null || req.category().isBlank()) ? null : req.category();
        String bankCategory = category == null ? "MIXED" : category;
        String bankKey = date + "|" + bankCategory + "|" + level;

        List<QA> bank = getOrBuildBank(bankKey, date, bankCategory, level);
        List<QA> topUp = getOrBuildTopUp(req, date, level, category);

        int maxCount = InterviewGenerationService.budgetFor(level)[1];
        return mergeAndAssemble(bank, topUp, req, maxCount);
    }

    // ---- Tier A: shared bank ----

    private List<QA> getOrBuildBank(String bankKey, String date, String bankCategory, String level) {
        List<QA> cached = bankCache.getIfPresent(bankKey);
        if (cached != null) return cached;

        DailyQuestionBankStore.Bank stored = bankStore.get(bankKey);
        if (stored != null && stored.isReady() && stored.qaListJson != null) {
            List<QA> pool = fromJson(stored.qaListJson);
            bankCache.put(bankKey, pool);
            return pool;
        }

        if (bankStore.acquireBuildLock(bankKey, date, bankCategory, level)) {
            try {
                BankPool built = generationService.generateBankPool(bankCategory, level);
                List<QA> pool = built.questions();
                bankBuilds.incrementAndGet();
                bankBuildCalls.addAndGet(built.openAiCalls());
                bankStore.markReady(bankKey, date, bankCategory, level, toJson(pool), mainCount(pool));
                bankCache.put(bankKey, pool);
                log.info("question_bank_built key={} size={} calls={}", bankKey, pool.size(), built.openAiCalls());
                return pool;
            } catch (Throwable t) {
                log.warn("question_bank_build_failed key={} error={}", bankKey, t.getMessage());
                try { bankStore.releaseLock(bankKey); } catch (Exception ignore) { /* best effort */ }
                return buildInlineFallback(bankCategory, level, bankKey);
            }
        }

        // Another replica is building — poll for READY, else build inline as a fallback.
        List<QA> polled = pollForReady(bankKey);
        if (polled != null) {
            bankCache.put(bankKey, polled);
            return polled;
        }
        return buildInlineFallback(bankCategory, level, bankKey);
    }

    private List<QA> buildInlineFallback(String bankCategory, String level, String bankKey) {
        log.warn("question_bank_inline_fallback key={}", bankKey);
        BankPool built = generationService.generateBankPool(bankCategory, level);
        bankBuilds.incrementAndGet();
        bankBuildCalls.addAndGet(built.openAiCalls());
        return built.questions();
    }

    private List<QA> pollForReady(String bankKey) {
        for (int i = 0; i < BackendDefaults.BANK_BUILD_POLL_MAX_RETRIES; i++) {
            try {
                Thread.sleep(BackendDefaults.BANK_BUILD_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            DailyQuestionBankStore.Bank b = bankStore.get(bankKey);
            if (b != null && b.isReady() && b.qaListJson != null) return fromJson(b.qaListJson);
        }
        return null;
    }

    // ---- Tier B: personalized top-up ----

    private List<QA> getOrBuildTopUp(GenerateRequest req, String date, String level, String category) {
        String cacheKey = date + "|" + fingerprint(req, level, category);

        List<QA> cached = topUpCache.getIfPresent(cacheKey);
        if (cached != null) return cached;

        String stored = topUpStore.get(cacheKey);
        if (stored != null) {
            List<QA> qa = fromJson(stored);
            topUpCache.put(cacheKey, qa);
            return qa;
        }

        List<QA> qa = generationService.generateTopUp(req);
        if (!qa.isEmpty()) {
            topUpStore.save(cacheKey, date, toJson(qa), qa.size());
            topUpCache.put(cacheKey, qa);
            topUpBuilds.incrementAndGet();
        }
        return qa;
    }

    // ---- Merge & assemble ----

    private List<QA> mergeAndAssemble(List<QA> bank, List<QA> topUp, GenerateRequest req, int maxCount) {
        List<QA> openers = bank.stream().filter(q -> "opener".equals(q.type())).toList();
        List<QA> formats = bank.stream().filter(q -> "format".equals(q.type())).toList();
        List<QA> bankMains = bank.stream().filter(CachedQuestionService::isMain).toList();

        List<QA> rankedBank = rankByRelevance(bankMains, req);

        List<QA> pool = new ArrayList<>();
        pool.addAll(openers);
        if (!formats.isEmpty()) pool.add(formats.get(0));
        pool.addAll(topUp);        // personalized first → wins de-dup and the maxCount cap
        pool.addAll(rankedBank);

        return InterviewGenerationService.assembleFinalQA(pool, maxCount);
    }

    private List<QA> rankByRelevance(List<QA> mains, GenerateRequest req) {
        Set<String> terms = relevanceTerms(req);
        List<QA> shuffled = new ArrayList<>(mains);
        // Seeded shuffle so a given candidate gets a stable order, but different
        // candidates get a different slice/order of the same shared bank.
        Collections.shuffle(shuffled, new Random(seedFor(req)));
        if (!terms.isEmpty()) {
            // Stable sort keeps the shuffled order as the tie-break among equal scores.
            shuffled.sort(Comparator.comparingInt((QA q) -> -relevanceScore(q, terms)));
        }
        return shuffled;
    }

    private static int relevanceScore(QA q, Set<String> terms) {
        if (q.question() == null) return 0;
        String text = q.question().toLowerCase();
        int score = 0;
        for (String t : terms) if (text.contains(t)) score++;
        return score;
    }

    private Set<String> relevanceTerms(GenerateRequest req) {
        Set<String> terms = new LinkedHashSet<>();
        if (req.preferredKeywords() != null) {
            for (String k : req.preferredKeywords()) {
                if (k != null && !k.isBlank()) terms.add(k.trim().toLowerCase());
            }
        }
        if (req.resumeText() != null) {
            for (String tok : req.resumeText().toLowerCase().split("[^a-z0-9+#.]+")) {
                if (tok.length() >= 4 && !RANK_STOPWORDS.contains(tok)) terms.add(tok);
                if (terms.size() >= 300) break;
            }
        }
        return terms;
    }

    private static boolean isMain(QA q) {
        return q.type() == null || q.type().isEmpty() || "main".equals(q.type());
    }

    private static int mainCount(List<QA> pool) {
        return (int) pool.stream().filter(CachedQuestionService::isMain).count();
    }

    // ---- Keys / hashing ----

    private String resolveLevel(String difficulty) {
        return difficulty != null && LEVELS.contains(difficulty) ? difficulty : DEFAULT_LEVEL;
    }

    private String today() {
        return LocalDate.now(ZoneId.of(BackendDefaults.BANK_TIMEZONE)).toString();
    }

    private String fingerprint(GenerateRequest req, String level, String category) {
        String keywords = req.preferredKeywords() == null ? "" : String.join(",", req.preferredKeywords());
        String basis = (req.resumeText() == null ? "" : req.resumeText())
                + " " + (req.jobDescriptionText() == null ? "" : req.jobDescriptionText())
                + " " + keywords
                + " " + level
                + " " + (category == null ? "" : category);
        return sha256(basis);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private long seedFor(GenerateRequest req) {
        String keywords = req.preferredKeywords() == null ? "" : String.join(",", req.preferredKeywords());
        byte[] digest;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            digest = md.digest(((req.resumeText() == null ? "" : req.resumeText()) + " " + keywords)
                    .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return keywords.hashCode();
        }
        long seed = 0;
        for (int i = 0; i < 8; i++) seed = (seed << 8) | (digest[i] & 0xFF);
        return seed;
    }

    // ---- JSON ----

    private String toJson(List<QA> qa) {
        try {
            return mapper.writeValueAsString(qa);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize question list", e);
        }
    }

    private List<QA> fromJson(String json) {
        try {
            return mapper.readValue(json, new TypeReference<List<QA>>() {});
        } catch (Exception e) {
            log.warn("question_cache_parse_failed error={}", e.getMessage());
            return List.of();
        }
    }

    // ---- Admin metrics ----

    public void recordFullyPersonalizedBypass() {
        fullyPersonalizedBypass.incrementAndGet();
    }

    public CacheStats bankCacheStats() { return bankCache.stats(); }
    public long bankCacheSize() { return bankCache.estimatedSize(); }
    public CacheStats topUpCacheStats() { return topUpCache.stats(); }
    public long topUpCacheSize() { return topUpCache.estimatedSize(); }

    public long interviewsServed() { return interviewsServed.get(); }
    public long bankBuilds() { return bankBuilds.get(); }
    public long bankBuildCalls() { return bankBuildCalls.get(); }
    public long topUpBuilds() { return topUpBuilds.get(); }
    public long fullyPersonalizedBypassCount() { return fullyPersonalizedBypass.get(); }

    public long estimatedCallsSaved() {
        long without = interviewsServed.get() * LIVE_GENERATION_EST_CALLS;
        long with = bankBuildCalls.get() + topUpBuilds.get();
        return Math.max(0, without - with);
    }

    public double estimatedSavingsUsd() {
        return estimatedCallsSaved() * BackendDefaults.CHAT_ESTIMATED_COST_PER_CALL_USD;
    }

    public int banksBuiltToday() {
        return bankStore.countReadyForDate(today());
    }

    public List<DailyQuestionBankStore.Bank> banksForToday() {
        return bankStore.listForDate(today());
    }
}
