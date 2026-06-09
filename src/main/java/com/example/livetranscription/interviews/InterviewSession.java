package com.example.livetranscription.interviews;

import java.time.Instant;

public class InterviewSession {
    private String sessionId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String startedAt;
    private String endedAt;
    private String label;
    private Boolean completed;

    private String candidateName;
    private String enrollmentId;
    private String createdBy;
    private String updatedBy;
    private String createdByEmail;
    private String updatedByEmail;

    private String difficulty;
    private String category;
    private String analysisMode;

    private String qaListJson;
    private String transcriptsJson;
    private String questionTimestampsJson;
    private String llmAnalysisJson;

    private Integer questionCount;
    private Double avgScore;
    private Long ttl;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getStartedAt() { return startedAt; }
    public void setStartedAt(String startedAt) { this.startedAt = startedAt; }

    public String getEndedAt() { return endedAt; }
    public void setEndedAt(String endedAt) { this.endedAt = endedAt; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public Boolean getCompleted() { return completed; }
    public void setCompleted(Boolean completed) { this.completed = completed; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getEnrollmentId() { return enrollmentId; }
    public void setEnrollmentId(String enrollmentId) { this.enrollmentId = enrollmentId; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }

    public String getCreatedByEmail() { return createdByEmail; }
    public void setCreatedByEmail(String createdByEmail) { this.createdByEmail = createdByEmail; }

    public String getUpdatedByEmail() { return updatedByEmail; }
    public void setUpdatedByEmail(String updatedByEmail) { this.updatedByEmail = updatedByEmail; }

    public String getDifficulty() { return difficulty; }
    public void setDifficulty(String difficulty) { this.difficulty = difficulty; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getAnalysisMode() { return analysisMode; }
    public void setAnalysisMode(String analysisMode) { this.analysisMode = analysisMode; }

    public String getQaListJson() { return qaListJson; }
    public void setQaListJson(String qaListJson) { this.qaListJson = qaListJson; }

    public String getTranscriptsJson() { return transcriptsJson; }
    public void setTranscriptsJson(String transcriptsJson) { this.transcriptsJson = transcriptsJson; }

    public String getQuestionTimestampsJson() { return questionTimestampsJson; }
    public void setQuestionTimestampsJson(String questionTimestampsJson) { this.questionTimestampsJson = questionTimestampsJson; }

    public String getLlmAnalysisJson() { return llmAnalysisJson; }
    public void setLlmAnalysisJson(String llmAnalysisJson) { this.llmAnalysisJson = llmAnalysisJson; }

    public Integer getQuestionCount() { return questionCount; }
    public void setQuestionCount(Integer questionCount) { this.questionCount = questionCount; }

    public Double getAvgScore() { return avgScore; }
    public void setAvgScore(Double avgScore) { this.avgScore = avgScore; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }
}
