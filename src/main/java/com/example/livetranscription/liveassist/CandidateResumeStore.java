package com.example.livetranscription.liveassist;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.DeleteItemRequest;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
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

    public void delete(String enrollmentId) {
        if (enrollmentId == null || enrollmentId.isBlank()) return;
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("enrollmentId", AttributeValue.builder().s(enrollmentId).build()))
                .build());
    }

    public List<Resume> listAll() {
        ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder().tableName(TABLE).build());
        List<Resume> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : resp.items()) {
            String enrollmentId = item.containsKey("enrollmentId") ? item.get("enrollmentId").s() : null;
            if (enrollmentId == null || enrollmentId.isBlank()) continue;
            String resumeText = item.containsKey("resumeText") ? item.get("resumeText").s() : "";
            String updatedAt = item.containsKey("updatedAt") ? item.get("updatedAt").s() : null;
            out.add(new Resume(enrollmentId, resumeText, updatedAt));
        }
        return out;
    }

    public record Resume(String enrollmentId, String resumeText, String updatedAt) {}
}
