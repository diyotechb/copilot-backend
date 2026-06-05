package com.example.livetranscription.liveassist;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class CandidateResumeStore {

    private static final String TABLE = "candidate_resumes";

    private final DynamoDbClient dynamoDbClient;

    public CandidateResumeStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public String get(String enrollmentId) {
        if (enrollmentId == null || enrollmentId.isBlank()) return null;
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("enrollmentId", AttributeValue.builder().s(enrollmentId).build()))
                .build()).item();
        if (item == null || item.isEmpty() || !item.containsKey("resumeText")) return null;
        return item.get("resumeText").s();
    }

    public void save(String enrollmentId, String resumeText) {
        if (enrollmentId == null || enrollmentId.isBlank()) return;
        if (resumeText == null || resumeText.isBlank()) return;
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("enrollmentId", AttributeValue.builder().s(enrollmentId).build());
        item.put("resumeText", AttributeValue.builder().s(resumeText).build());
        item.put("updatedAt", AttributeValue.builder().s(Instant.now().toString()).build());
        long ttl = Instant.now().plusSeconds(90L * 24 * 3600).getEpochSecond();
        item.put("ttl", AttributeValue.builder().n(Long.toString(ttl)).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }
}
