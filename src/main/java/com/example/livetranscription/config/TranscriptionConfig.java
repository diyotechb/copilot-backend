package com.example.livetranscription.config;

public class TranscriptionConfig {

    public static final double END_OF_TURN_THRESHOLD = 0.4;
    public static final int MIN_SILENCE_THRESHOLD = 400;
    public static final int MAX_TURN_SILENCE = 1280;
    public static final double VAD_THRESHOLD = 0.4;

    public static final int SAMPLE_RATE = 16000;

    public static final String ASSEMBLY_AI_WS_URL = "wss://streaming.assemblyai.com/v3/ws";

    public static final String SPEECH_MODEL = "universal-streaming-english";

    // Safety net for tabs left open with a hot mic; we don't close on silence
    // since legit users pause for long stretches.
    public static final long MAX_SESSION_DURATION_HOURS = 4L;

    private TranscriptionConfig() {}
}
