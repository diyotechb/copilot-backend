package com.example.livetranscription.liveassist;

import java.time.Instant;

public class LiveAssistSession {
    private String sessionId;
    private String conversationId;
    private String status;
    private Instant createdAt;
    private Instant updatedAt;
    private String label;
    private String resumeText;
    private String finalReport;
    private Long ttl;
    private String candidateName;
    private String enrollmentId;
    private String task;
    private String interviewDateTime;
    private String client;
    private String callTaker;
    private String vendor;
    private String duration;
    private String outcome;
    private String jobDescription;
    private String notes;

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getConversationId() { return conversationId; }
    public void setConversationId(String conversationId) { this.conversationId = conversationId; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }

    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }

    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }

    public String getResumeText() { return resumeText; }
    public void setResumeText(String resumeText) { this.resumeText = resumeText; }

    public String getFinalReport() { return finalReport; }
    public void setFinalReport(String finalReport) { this.finalReport = finalReport; }

    public Long getTtl() { return ttl; }
    public void setTtl(Long ttl) { this.ttl = ttl; }

    public String getCandidateName() { return candidateName; }
    public void setCandidateName(String candidateName) { this.candidateName = candidateName; }

    public String getEnrollmentId() { return enrollmentId; }
    public void setEnrollmentId(String enrollmentId) { this.enrollmentId = enrollmentId; }

    public String getTask() { return task; }
    public void setTask(String task) { this.task = task; }

    public String getInterviewDateTime() { return interviewDateTime; }
    public void setInterviewDateTime(String interviewDateTime) { this.interviewDateTime = interviewDateTime; }

    public String getClient() { return client; }
    public void setClient(String client) { this.client = client; }

    public String getCallTaker() { return callTaker; }
    public void setCallTaker(String callTaker) { this.callTaker = callTaker; }

    public String getVendor() { return vendor; }
    public void setVendor(String vendor) { this.vendor = vendor; }

    public String getDuration() { return duration; }
    public void setDuration(String duration) { this.duration = duration; }

    public String getOutcome() { return outcome; }
    public void setOutcome(String outcome) { this.outcome = outcome; }

    public String getJobDescription() { return jobDescription; }
    public void setJobDescription(String jobDescription) { this.jobDescription = jobDescription; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
