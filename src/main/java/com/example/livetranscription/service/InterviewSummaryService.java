package com.example.livetranscription.service;

import com.example.livetranscription.model.InterviewSummary;

import java.util.List;

public interface InterviewSummaryService {
    InterviewSummary generateSummary(String sessionId, List<String> conversation);
}
