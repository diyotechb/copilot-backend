package com.example.service;

import com.example.model.InterviewSession;

public interface InterviewService {
    InterviewSession startInterview(String candidateId);

    void completeInterview(String sessionId);
}
