package com.example.livetranscription.config;

/**
 * Centralized configuration for transcription-related settings.
 * This class serves as the single source of truth for all thresholds and defaults,
 * replacing static values in individual services and property files.
 */
public class TranscriptionConfig {

    // ── V1 params (original /realtime endpoint, kept unchanged) ──
    public static final int MIN_SILENCE_THRESHOLD = 500;
    public static final int MAX_SILENCE_THRESHOLD = 2000;
    public static final double END_OF_TURN_THRESHOLD = 0.1;
    public static final double VAD_THRESHOLD = 0.1;

    // ── V2 params (direct /realtime-v2 endpoint, tuned for V3 universal-streaming) ──
    // Matches AssemblyAI's own sample code values for best real-time accuracy.
    public static final double V2_END_OF_TURN_THRESHOLD = 0.4;
    public static final int V2_MIN_SILENCE_THRESHOLD = 400;
    public static final int V2_MAX_TURN_SILENCE = 1280;
    public static final double V2_VAD_THRESHOLD = 0.4;

    /**
     * Default sample rate for audio processing.
     * 16000 is preferred for Universal-3 Pro real-time.
     */
    public static final int SAMPLE_RATE = 16000;

    /**
     * Default WebSocket URL for AssemblyAI Realtime service.
     */
    public static final String ASSEMBLY_AI_WS_URL = "wss://streaming.assemblyai.com/v3/ws";

    /**
     * Default speech model for AssemblyAI V3.
     * universal-streaming-english is the fastest real-time model.
     */
    public static final String SPEECH_MODEL = "universal-streaming-english";

    // Prevent instantiation
    private TranscriptionConfig() {}
}
