package com.example.livetranscription.config;

public final class BackendDefaults {

    public static final String OPENAI_CHAT_MODEL = "gpt-4o-mini";
    public static final String VOICE_CHAT_MODEL = "gpt-4o-mini";
    public static final double VOICE_CHAT_TEMPERATURE = 0.4;
    public static final int    VOICE_CHAT_MAX_TOKENS  = 220;
    public static final String OPENAI_ANALYSIS_MODEL = "gpt-4o-mini";
    public static final String OPENAI_TTS_MODEL = "tts-1";
    public static final String OPENAI_TTS_FORMAT = "mp3";
    public static final long OPENAI_TTS_TIMEOUT_SECONDS = 25;

    public static final String ASSEMBLY_AI_BASE_URL = "https://api.assemblyai.com/v2";
    public static final long ASSEMBLY_AI_POLL_INTERVAL_MS = 2000L;
    public static final int ASSEMBLY_AI_MAX_POLL_RETRIES = 60;

    public static final long MULTIPART_MAX_FILE_SIZE_BYTES    = 50L * 1024 * 1024;
    public static final long MULTIPART_MAX_REQUEST_SIZE_BYTES = 50L * 1024 * 1024;

    // Spring's port-wildcard in allowedOriginPatterns requires the [*] brackets.
    public static final String[] CORS_LOCALHOST_PATTERNS = {
            "http://localhost:[*]",
            "http://127.0.0.1:[*]"
    };

    public static final int RATE_LIMIT_CAPACITY     = 100;
    public static final int RATE_LIMIT_REFILL_TOKENS = 60;
    public static final long RATE_LIMIT_REFILL_SECONDS = 60L;

    public static final int MAX_RESUME_CHARS              = 50_000;
    public static final int MAX_JOB_DESCRIPTION_CHARS     = 20_000;
    public static final int MAX_SESSION_LABEL_CHARS       = 200;
    public static final int MAX_SESSION_NOTES_CHARS       = 5_000;
    public static final int MAX_PREFERRED_KEYWORDS        = 20;
    public static final int MAX_PREFERRED_KEYWORD_CHARS   = 100;
    public static final int MAX_TTS_TEXT_CHARS            = 5_000;
    public static final int MAX_SAMPLE_KEYWORDS_CHARS     = 500;
    public static final int MAX_ANALYZE_QA_ITEMS          = 100;
    public static final int MAX_ANALYZE_QUESTION_CHARS    = 5_000;
    public static final int MAX_ANALYZE_ANSWER_CHARS      = 20_000;
    public static final int MAX_ANALYZE_TRANSCRIPT_CHARS  = 50_000;

    // A session whose status is still ACTIVE but hasn't been written to in this
    // long is treated as abandoned (shown as ENDED) — no client sent a final
    // update. Read-time only; the stored row is left for TTL to purge.
    public static final long SESSION_ACTIVE_WINDOW_SECONDS = 15 * 60;
    // Server-side cache window for the admin active-interview count so the 10s
    // dashboard auto-refresh doesn't scan DynamoDB on every poll.
    public static final long ADMIN_ACTIVE_COUNT_CACHE_MS = 30_000;

    // ---- Daily cached interview questions ----
    // The shared bank's date bucket is computed in this zone so a "day" lines up
    // with the team's working day (matches ReminderScheduler's Eastern schedule).
    public static final String BANK_TIMEZONE = "America/New_York";
    // Target number of distinct main questions to hold in one shared bank. The
    // bank is sampled per candidate, so it holds well over a single interview's
    // worth to give different candidates a different slice.
    public static final int  QUESTION_BANK_TARGET_MAIN = 60;
    // Max generation rounds while building a bank (3 parallel batches each).
    public static final int  QUESTION_BANK_MAX_ROUNDS  = 6;
    // Both caches keep yesterday's rows alive a little past midnight, then
    // DynamoDB TTL auto-deletes them. Freshness is daily via the date in the key.
    public static final long QUESTION_BANK_RETENTION_DAYS    = 2;
    public static final long PERSONALIZED_CACHE_RETENTION_DAYS = 2;
    // Per-candidate personalized top-up: ~5 resume-anchored + ~5 keyword-anchored
    // are SERVED per interview, sampled from a larger cached pool so same-day
    // re-runs get a different slice without another OpenAI call.
    public static final int  TOPUP_RESUME_QUESTIONS  = 5;
    public static final int  TOPUP_KEYWORD_QUESTIONS = 5;
    public static final int  TOPUP_POOL_RESUME_QUESTIONS  = 8;
    public static final int  TOPUP_POOL_KEYWORD_QUESTIONS = 8;
    // Build lock: a BUILDING marker older than this (a dead build) can be taken
    // over by another replica so a crash can't wedge a bucket for the day.
    public static final long BANK_BUILD_LOCK_TTL_SECONDS  = 120L;
    public static final long BANK_BUILD_POLL_INTERVAL_MS  = 1500L;
    public static final int  BANK_BUILD_POLL_MAX_RETRIES  = 20;
    // Rough gpt-4o-mini cost per generate batch (Jan 2026 pricing) — used only to
    // show an estimated-dollars-saved figure on the admin status page.
    public static final double CHAT_ESTIMATED_COST_PER_CALL_USD = 0.0015;

    private BackendDefaults() {}
}
