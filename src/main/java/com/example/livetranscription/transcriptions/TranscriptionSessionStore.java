package com.example.livetranscription.transcriptions;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TranscriptionSessionStore {

    private static final String TABLE = "transcription_sessions";

    private final DynamoDbClient dynamoDbClient;

    public TranscriptionSessionStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public void save(TranscriptionSession s) {
        s.setUpdatedAt(Instant.now());
        Map<String, AttributeValue> item = new HashMap<>();
        putS(item, "sessionId", s.getSessionId());
        putS(item, "status", s.getStatus());
        putS(item, "createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        putS(item, "updatedAt", s.getUpdatedAt().toString());
        putS(item, "label", s.getLabel());
        putS(item, "category", s.getCategory());
        putN(item, "startedAt", s.getStartedAt());
        putS(item, "linesJson", s.getLinesJson());
        putN(item, "lineCount", s.getLineCount() != null ? s.getLineCount().longValue() : null);
        putN(item, "durationMs", s.getDurationMs());
        putS(item, "notes", s.getNotes());
        putS(item, "createdBy", s.getCreatedBy());
        putS(item, "updatedBy", s.getUpdatedBy());
        putS(item, "createdByEmail", s.getCreatedByEmail());
        putS(item, "updatedByEmail", s.getUpdatedByEmail());
        putS(item, "candidateName", s.getCandidateName());
        putS(item, "enrollmentId", s.getEnrollmentId());
        putS(item, "task", s.getTask());
        putS(item, "interviewDateTime", s.getInterviewDateTime());
        putS(item, "client", s.getClient());
        putS(item, "callTaker", s.getCallTaker());
        putS(item, "vendor", s.getVendor());
        putS(item, "duration", s.getDuration());
        putS(item, "outcome", s.getOutcome());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }

    public TranscriptionSession get(String sessionId) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build()).item();
        if (item == null || item.isEmpty()) return null;
        return toSession(item);
    }

    public List<TranscriptionSession> listAll() {
        List<Map<String, AttributeValue>> items = dynamoDbClient.scan(
                ScanRequest.builder().tableName(TABLE).build()).items();
        List<TranscriptionSession> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) out.add(toSession(item));
        return out;
    }

    public void delete(String sessionId) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build());
    }

    private TranscriptionSession toSession(Map<String, AttributeValue> item) {
        TranscriptionSession s = new TranscriptionSession();
        s.setSessionId(getS(item, "sessionId"));
        s.setStatus(getS(item, "status"));
        String createdAt = getS(item, "createdAt");
        if (createdAt != null) s.setCreatedAt(Instant.parse(createdAt));
        String updatedAt = getS(item, "updatedAt");
        if (updatedAt != null) s.setUpdatedAt(Instant.parse(updatedAt));
        s.setLabel(getS(item, "label"));
        s.setCategory(getS(item, "category"));
        s.setStartedAt(getN(item, "startedAt"));
        s.setLinesJson(getS(item, "linesJson"));
        Long lineCount = getN(item, "lineCount");
        if (lineCount != null) s.setLineCount(lineCount.intValue());
        s.setDurationMs(getN(item, "durationMs"));
        s.setNotes(getS(item, "notes"));
        s.setCreatedBy(getS(item, "createdBy"));
        s.setUpdatedBy(getS(item, "updatedBy"));
        s.setCreatedByEmail(getS(item, "createdByEmail"));
        s.setUpdatedByEmail(getS(item, "updatedByEmail"));
        s.setCandidateName(getS(item, "candidateName"));
        s.setEnrollmentId(getS(item, "enrollmentId"));
        s.setTask(getS(item, "task"));
        s.setInterviewDateTime(getS(item, "interviewDateTime"));
        s.setClient(getS(item, "client"));
        s.setCallTaker(getS(item, "callTaker"));
        s.setVendor(getS(item, "vendor"));
        s.setDuration(getS(item, "duration"));
        s.setOutcome(getS(item, "outcome"));
        return s;
    }

    private static void putS(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) item.put(key, AttributeValue.builder().s(value).build());
    }

    private static void putN(Map<String, AttributeValue> item, String key, Long value) {
        if (value != null) item.put(key, AttributeValue.builder().n(value.toString()).build());
    }

    private static String getS(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }

    private static Long getN(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? Long.parseLong(item.get(key).n()) : null;
    }
}
