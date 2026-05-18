package com.example.livetranscription.config;

public final class BackendDefaults {

    public static final String OPENAI_CHAT_MODEL = "gpt-4o-mini";
    public static final String OPENAI_ANALYSIS_MODEL = "gpt-4o-mini";
    public static final String OPENAI_TTS_MODEL = "tts-1";
    public static final String OPENAI_TTS_FORMAT = "mp3";

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
    public static final int MAX_PREFERRED_KEYWORDS        = 20;
    public static final int MAX_PREFERRED_KEYWORD_CHARS   = 100;
    public static final int MAX_TTS_TEXT_CHARS            = 5_000;
    public static final int MAX_SAMPLE_KEYWORDS_CHARS     = 500;
    public static final int MAX_ANALYZE_QA_ITEMS          = 100;
    public static final int MAX_ANALYZE_QUESTION_CHARS    = 5_000;
    public static final int MAX_ANALYZE_ANSWER_CHARS      = 20_000;
    public static final int MAX_ANALYZE_TRANSCRIPT_CHARS  = 50_000;

    private BackendDefaults() {}
}
