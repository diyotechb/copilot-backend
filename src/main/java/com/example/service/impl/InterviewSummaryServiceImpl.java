package com.example.service.impl;

import com.example.model.InterviewSummary;
import com.example.service.InterviewSummaryService;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class InterviewSummaryServiceImpl implements InterviewSummaryService {
    @Override
    public InterviewSummary generateSummary(String sessionId, List<String> conversation) {
        // TODO: Use OpenAI to generate interview summary, strengths, weaknesses, score
        return new InterviewSummary();
    }
}
