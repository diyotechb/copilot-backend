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
    public static final double END_OF_TURN_THRESHOLD = 0.3;

    /**
     * Default sample rate for audio processing.
     */
    public static final int SAMPLE_RATE = 48000;

    /**
     * Default WebSocket URL for AssemblyAI Realtime service.
     */
    public static final String ASSEMBLY_AI_WS_URL = "wss://streaming.assemblyai.com/v3/ws";

    // Prevent instantiation
    private TranscriptionConfig() {}
}
