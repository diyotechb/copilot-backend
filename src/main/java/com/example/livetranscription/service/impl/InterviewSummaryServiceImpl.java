package com.example.livetranscription.service.impl;

import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.InterviewSummary;
import com.example.livetranscription.service.InterviewSummaryService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class InterviewSummaryServiceImpl implements InterviewSummaryService {

    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;

    @Override
    public InterviewSummary generateSummary(String sessionId, List<String> conversation) {
        InterviewSummary summary = new InterviewSummary();
        summary.setStrengths(List.of("Kafka fundamentals", "Spring Boot"));
        summary.setWeaknesses(List.of("OAuth2 token flow", "distributed transactions"));
        summary.setScore(7);
        dynamoPersistenceService.saveInterviewSummary(sessionId, summary.toString());
        return summary;
    }
}
