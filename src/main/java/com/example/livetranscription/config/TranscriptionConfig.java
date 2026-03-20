package com.example.livetranscription.config;

/**
 * Centralized configuration for transcription-related settings.
 * This class serves as the single source of truth for all thresholds and defaults,
 * replacing static values in individual services and property files.
 */
public class TranscriptionConfig {

    /**
     * Minimum silence duration (in milliseconds) before the AI engine considers 
     * a turn to have potentially ended when confident.
     */
    public static final int MIN_SILENCE_THRESHOLD = 500;

    /**
     * Absolute maximum silence duration (in milliseconds) allowed before a turn
     * is forced to conclude, regardless of AI confidence.
     */
    public static final int MAX_SILENCE_THRESHOLD = 2000;

    /**
     * confidence threshold (0.0 to 1.0) for the end-of-turn detection.
     * Lower values make the engine more aggressive in ending turns.
     */
    public static final double END_OF_TURN_THRESHOLD = 0.1;

    /**
     * VAD threshold (0.0 to 1.0) for speech detection.
     * Lower values are more sensitive to speech.
     */
    public static final double VAD_THRESHOLD = 0.1;

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
