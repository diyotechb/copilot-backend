package com.example.livetranscription.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class AssemblyAIResponse {
    @JsonProperty("message_type")
    private String messageType;
    
    private String text;
    private String transcript;
    private String utterance;
    private Punctuated punctuated;
    private List<Word> words;
    
    @JsonProperty("is_final")
    private Boolean isFinal;
    
    @JsonProperty("final")
    private Boolean finalFlag;
    
    @JsonProperty("end_of_turn")
    private Boolean endOfTurn;
    
    @JsonProperty("audio_start")
    private Long audioStart;
    
    @JsonProperty("audio_end")
    private Long audioEnd;

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getText() { return text; }
    public void setText(String text) { this.text = text; }

    public String getTranscript() { return transcript; }
    public void setTranscript(String transcript) { this.transcript = transcript; }

    public String getUtterance() { return utterance; }
    public void setUtterance(String utterance) { this.utterance = utterance; }

    public Punctuated getPunctuated() { return punctuated; }
    public void setPunctuated(Punctuated punctuated) { this.punctuated = punctuated; }

    public List<Word> getWords() { return words; }
    public void setWords(List<Word> words) { this.words = words; }

    public Boolean getIsFinal() { return isFinal; }
    public void setIsFinal(Boolean isFinal) { this.isFinal = isFinal; }

    public Boolean getFinalFlag() { return finalFlag; }
    public void setFinalFlag(Boolean finalFlag) { this.finalFlag = finalFlag; }

    public Boolean getEndOfTurn() { return endOfTurn; }
    public void setEndOfTurn(Boolean endOfTurn) { this.endOfTurn = endOfTurn; }

    public Long getAudioStart() { return audioStart; }
    public void setAudioStart(Long audioStart) { this.audioStart = audioStart; }

    public Long getAudioEnd() { return audioEnd; }
    public void setAudioEnd(Long audioEnd) { this.audioEnd = audioEnd; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Punctuated {
        private String transcript;
        public String getTranscript() { return transcript; }
        public void setTranscript(String transcript) { this.transcript = transcript; }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Word {
        private String text;
        private Long start;
        private Long end;
        private Double confidence;

        public String getText() { return text; }
        public void setText(String text) { this.text = text; }

        public Long getStart() { return start; }
        public void setStart(Long start) { this.start = start; }

        public Long getEnd() { return end; }
        public void setEnd(Long end) { this.end = end; }

        public Double getConfidence() { return confidence; }
        public void setConfidence(Double confidence) { this.confidence = confidence; }
    }
}
