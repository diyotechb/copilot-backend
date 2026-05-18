package com.example.livetranscription.config;

/**
 * Centralized configuration for transcription-related settings.
 * Tuned for AssemblyAI V3 universal-streaming.
 */
public class TranscriptionConfig {

    public static final double END_OF_TURN_THRESHOLD = 0.4;
    public static final int MIN_SILENCE_THRESHOLD = 400;
    public static final int MAX_TURN_SILENCE = 1280;
    public static final double VAD_THRESHOLD = 0.4;

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
     */
    public static final String SPEECH_MODEL = "universal-streaming-english";

    private TranscriptionConfig() {}
}
