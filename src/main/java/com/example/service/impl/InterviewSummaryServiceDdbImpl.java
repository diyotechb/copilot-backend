package com.example.service.impl;

import com.example.model.InterviewSummary;
import com.example.service.InterviewSummaryService;
import com.example.dynamodb.DynamoPersistenceService;
import com.example.openai.OpenAIConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class InterviewSummaryServiceDdbImpl implements InterviewSummaryService {
    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;
    @Autowired
    private OpenAIConversationService openAIConversationService;

    @Override
    public InterviewSummary generateSummary(String sessionId, List<String> conversation) {
        // TODO: Call OpenAI to generate summary, strengths, weaknesses, score
        // Save to DynamoDB
        InterviewSummary summary = new InterviewSummary();
        summary.setStrengths(List.of("Kafka fundamentals", "Spring Boot"));
        summary.setWeaknesses(List.of("OAuth2 token flow", "distributed transactions"));
        summary.setScore(7);
        dynamoPersistenceService.saveInterviewSummary(sessionId, summary.toString());
        return summary;
    }
}
