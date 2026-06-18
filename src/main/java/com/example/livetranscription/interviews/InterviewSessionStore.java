package com.example.livetranscription.interviews;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class InterviewSessionStore {

    private static final String TABLE = "interview_sessions";
    private static final String ENROLLMENT_INDEX = "gsi_enrollment";
    private static final long RETENTION_DAYS = 60;

    private final DynamoDbClient dynamoDbClient;

    public InterviewSessionStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public void save(InterviewSession s) {
        s.setUpdatedAt(Instant.now());
        Map<String, AttributeValue> item = new HashMap<>();
        putS(item, "sessionId", s.getSessionId());
        putS(item, "status", s.getStatus());
        putS(item, "createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        putS(item, "updatedAt", s.getUpdatedAt().toString());
        putS(item, "startedAt", s.getStartedAt());
        putS(item, "endedAt", s.getEndedAt());
        putS(item, "label", s.getLabel());
        putBool(item, "completed", s.getCompleted());
        putS(item, "candidateName", s.getCandidateName());
        // enrollmentId is the GSI key — DynamoDB rejects an empty string there,
        // so only write it when a candidate was actually selected.
        if (s.getEnrollmentId() != null && !s.getEnrollmentId().isBlank()) {
            putS(item, "enrollmentId", s.getEnrollmentId());
        }
        putS(item, "createdBy", s.getCreatedBy());
        putS(item, "updatedBy", s.getUpdatedBy());
        putS(item, "createdByEmail", s.getCreatedByEmail());
        putS(item, "updatedByEmail", s.getUpdatedByEmail());
        putS(item, "difficulty", s.getDifficulty());
        putS(item, "category", s.getCategory());
        putS(item, "analysisMode", s.getAnalysisMode());
        putS(item, "qaListJson", s.getQaListJson());
        putS(item, "transcriptsJson", s.getTranscriptsJson());
        putS(item, "questionTimestampsJson", s.getQuestionTimestampsJson());
        putS(item, "llmAnalysisJson", s.getLlmAnalysisJson());
        putN(item, "questionCount", s.getQuestionCount() != null ? s.getQuestionCount().longValue() : null);
        putD(item, "avgScore", s.getAvgScore());
        Instant base = s.getCreatedAt() != null ? s.getCreatedAt() : s.getUpdatedAt();
        long ttl = base.plusSeconds(RETENTION_DAYS * 24L * 3600L).getEpochSecond();
        s.setTtl(ttl);
        item.put("ttl", AttributeValue.builder().n(Long.toString(ttl)).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }

    public InterviewSession get(String sessionId) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build()).item();
        if (item == null || item.isEmpty()) return null;
        return toSession(item);
    }

    public int countByStatus(String status) {
        ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE)
                .filterExpression("#s = :st")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(":st", AttributeValue.builder().s(status).build()))
                .select(Select.COUNT)
                .build());
        return resp.count();
    }

    public int countActiveSince(String sinceIso) {
        ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE)
                .filterExpression("#s = :st AND updatedAt >= :since")
                .expressionAttributeNames(Map.of("#s", "status"))
                .expressionAttributeValues(Map.of(
                        ":st", AttributeValue.builder().s("ACTIVE").build(),
                        ":since", AttributeValue.builder().s(sinceIso).build()))
                .select(Select.COUNT)
                .build());
        return resp.count();
    }

    public List<InterviewSession> listAll() {
        List<Map<String, AttributeValue>> items = dynamoDbClient.scan(
                ScanRequest.builder().tableName(TABLE).build()).items();
        List<InterviewSession> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) out.add(toSession(item));
        return out;
    }

    public List<InterviewSession> listByEnrollment(String enrollmentId) {
        if (enrollmentId == null || enrollmentId.isBlank()) return new ArrayList<>();
        QueryResponse resp = dynamoDbClient.query(QueryRequest.builder()
                .tableName(TABLE)
                .indexName(ENROLLMENT_INDEX)
                .keyConditionExpression("enrollmentId = :e")
                .expressionAttributeValues(Map.of(":e", AttributeValue.builder().s(enrollmentId).build()))
                .build());
        List<InterviewSession> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : resp.items()) out.add(toSession(item));
        return out;
    }

    public void delete(String sessionId) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build());
    }

    private InterviewSession toSession(Map<String, AttributeValue> item) {
        InterviewSession s = new InterviewSession();
        s.setSessionId(getS(item, "sessionId"));
        s.setStatus(getS(item, "status"));
        String createdAt = getS(item, "createdAt");
        if (createdAt != null) s.setCreatedAt(Instant.parse(createdAt));
        String updatedAt = getS(item, "updatedAt");
        if (updatedAt != null) s.setUpdatedAt(Instant.parse(updatedAt));
        s.setStartedAt(getS(item, "startedAt"));
        s.setEndedAt(getS(item, "endedAt"));
        s.setLabel(getS(item, "label"));
        s.setCompleted(getBool(item, "completed"));
        s.setCandidateName(getS(item, "candidateName"));
        s.setEnrollmentId(getS(item, "enrollmentId"));
        s.setCreatedBy(getS(item, "createdBy"));
        s.setUpdatedBy(getS(item, "updatedBy"));
        s.setCreatedByEmail(getS(item, "createdByEmail"));
        s.setUpdatedByEmail(getS(item, "updatedByEmail"));
        s.setDifficulty(getS(item, "difficulty"));
        s.setCategory(getS(item, "category"));
        s.setAnalysisMode(getS(item, "analysisMode"));
        s.setQaListJson(getS(item, "qaListJson"));
        s.setTranscriptsJson(getS(item, "transcriptsJson"));
        s.setQuestionTimestampsJson(getS(item, "questionTimestampsJson"));
        s.setLlmAnalysisJson(getS(item, "llmAnalysisJson"));
        Long questionCount = getN(item, "questionCount");
        if (questionCount != null) s.setQuestionCount(questionCount.intValue());
        s.setAvgScore(getD(item, "avgScore"));
        if (item.containsKey("ttl")) s.setTtl(Long.parseLong(item.get("ttl").n()));
        return s;
    }

    private static void putS(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) item.put(key, AttributeValue.builder().s(value).build());
    }

    private static void putN(Map<String, AttributeValue> item, String key, Long value) {
        if (value != null) item.put(key, AttributeValue.builder().n(value.toString()).build());
    }

    private static void putD(Map<String, AttributeValue> item, String key, Double value) {
        if (value != null) item.put(key, AttributeValue.builder().n(value.toString()).build());
    }

    private static void putBool(Map<String, AttributeValue> item, String key, Boolean value) {
        if (value != null) item.put(key, AttributeValue.builder().bool(value).build());
    }

    private static String getS(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }

    private static Long getN(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? Long.parseLong(item.get(key).n()) : null;
    }

    private static Double getD(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? Double.parseDouble(item.get(key).n()) : null;
    }

    private static Boolean getBool(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) && item.get(key).bool() != null ? item.get(key).bool() : null;
    }
}
