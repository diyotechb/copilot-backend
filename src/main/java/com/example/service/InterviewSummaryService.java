package com.example.service;

import com.example.model.InterviewSummary;
import java.util.List;

public interface InterviewSummaryService {
    InterviewSummary generateSummary(String sessionId, List<String> conversation);
}
