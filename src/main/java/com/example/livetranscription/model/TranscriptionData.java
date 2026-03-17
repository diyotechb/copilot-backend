package com.example.livetranscription.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public class TranscriptionData {
    private String text;
    
    @JsonProperty("end_of_turn")
    private boolean endOfTurn;
    
    @JsonProperty("audio_start")
    private Long audioStart;
    
    @JsonProperty("audio_end")
    private Long audioEnd;
    
    private List<AssemblyAIResponse.Word> words;

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public boolean isEndOfTurn() { return endOfTurn; }
    public void setEndOfTurn(boolean endOfTurn) { this.endOfTurn = endOfTurn; }

    public Long getAudioStart() { return audioStart; }
    public void setAudioStart(Long audioStart) { this.audioStart = audioStart; }

    public Long getAudioEnd() { return audioEnd; }
    public void setAudioEnd(Long audioEnd) { this.audioEnd = audioEnd; }

    public List<AssemblyAIResponse.Word> getWords() { return words; }
    public void setWords(List<AssemblyAIResponse.Word> words) { this.words = words; }
}
