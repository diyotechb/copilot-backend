package com.example.livetranscription.service;

import com.example.livetranscription.model.InterviewSession;

public interface InterviewService {
    InterviewSession startInterview(String candidateId);
    void completeInterview(String sessionId);
}
