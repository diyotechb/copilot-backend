package com.example.livetranscription.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class TranscriptionData {
    private String text;
    private String transcript;
    
    @JsonProperty("end_of_turn")
    private boolean endOfTurn;

    @JsonProperty("eot")
    private boolean eot;

    @JsonProperty("isFinal")
    private boolean isFinal;
    
    @JsonProperty("audio_start")
    private Long audioStart;
    
    @JsonProperty("audio_end")
    private Long audioEnd;
    
    private List<AssemblyAIResponse.Word> words;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getTranscript() { return transcript; }
    public void setTranscript(String transcript) { this.transcript = transcript; }

    public boolean isEndOfTurn() { return endOfTurn; }
    public void setEndOfTurn(boolean endOfTurn) { 
        this.endOfTurn = endOfTurn; 
        this.eot = endOfTurn;
    }

    public boolean isEot() { return eot; }
    public void setEot(boolean eot) { 
        this.eot = eot;
        this.endOfTurn = eot;
        this.isFinal = eot;
    }

    public boolean isFinal() { return isFinal; }
    public void setFinal(boolean isFinal) {
        this.isFinal = isFinal;
        this.endOfTurn = isFinal;
        this.eot = isFinal;
    }

    public Long getAudioStart() { return audioStart; }
    public void setAudioStart(Long audioStart) { this.audioStart = audioStart; }

    public Long getAudioEnd() { return audioEnd; }
    public void setAudioEnd(Long audioEnd) { this.audioEnd = audioEnd; }

    public List<AssemblyAIResponse.Word> getWords() { return words; }
    public void setWords(List<AssemblyAIResponse.Word> words) { this.words = words; }
}
