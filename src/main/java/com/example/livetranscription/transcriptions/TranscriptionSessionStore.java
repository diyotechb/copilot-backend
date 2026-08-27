package com.example.livetranscription.transcriptions;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class TranscriptionSessionStore {

    private static final String TABLE = "transcription_sessions";

    private static final List<String> SUMMARY_FIELDS = List.of(
            "sessionId", "status", "createdAt", "updatedAt", "label", "category", "lineCount",
            "createdBy", "updatedBy", "createdByEmail", "updatedByEmail", "candidateName",
            "enrollmentId", "task", "interviewDateTime", "client", "callTaker", "vendor",
            "duration", "outcome", "deleted", "ttl");

    private static final Map<String, String> SUMMARY_NAMES;
    private static final String SUMMARY_PROJECTION;

    static {
        Map<String, String> names = new LinkedHashMap<>();
        for (int i = 0; i < SUMMARY_FIELDS.size(); i++) names.put("#f" + i, SUMMARY_FIELDS.get(i));
        SUMMARY_NAMES = Map.copyOf(names);
        SUMMARY_PROJECTION = String.join(", ", names.keySet());
    }

    private static final int BATCH_GET_MAX_KEYS = 100;

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
        putBool(item, "deleted", s.getDeleted());
        long ttl = expiryEpochFor(s);
        s.setTtl(ttl);
        item.put("ttl", AttributeValue.builder().n(Long.toString(ttl)).build());
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

    public List<TranscriptionSession> listSummaries() {
        List<TranscriptionSession> out = new ArrayList<>();
        Map<String, AttributeValue> startKey = null;
        do {
            ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder()
                    .tableName(TABLE)
                    .projectionExpression(SUMMARY_PROJECTION)
                    .expressionAttributeNames(SUMMARY_NAMES)
                    .exclusiveStartKey(startKey)
                    .build());
            for (Map<String, AttributeValue> item : resp.items()) out.add(toSession(item));
            startKey = resp.lastEvaluatedKey();
        } while (startKey != null && !startKey.isEmpty());
        return out;
    }

    public Map<String, String> linesJsonFor(Collection<String> sessionIds) {
        Map<String, String> out = new HashMap<>();
        List<String> ids = sessionIds.stream().filter(Objects::nonNull).distinct().collect(Collectors.toList());
        for (int i = 0; i < ids.size(); i += BATCH_GET_MAX_KEYS) {
            List<String> chunk = ids.subList(i, Math.min(i + BATCH_GET_MAX_KEYS, ids.size()));
            Map<String, KeysAndAttributes> request = Map.of(TABLE, KeysAndAttributes.builder()
                    .keys(chunk.stream()
                            .map(id -> Map.of("sessionId", AttributeValue.builder().s(id).build()))
                            .collect(Collectors.toList()))
                    .projectionExpression("#k, #l")
                    .expressionAttributeNames(Map.of("#k", "sessionId", "#l", "linesJson"))
                    .build());
            while (!request.isEmpty()) {
                BatchGetItemResponse resp = dynamoDbClient.batchGetItem(
                        BatchGetItemRequest.builder().requestItems(request).build());
                for (Map<String, AttributeValue> item : resp.responses().getOrDefault(TABLE, List.of())) {
                    String id = getS(item, "sessionId");
                    if (id != null) out.put(id, getS(item, "linesJson"));
                }
                request = resp.unprocessedKeys();
            }
        }
        return out;
    }

    public static long expiryEpochFor(TranscriptionSession s) {
        Instant base = s.getCreatedAt() != null ? s.getCreatedAt() : s.getUpdatedAt();
        return BackendDefaults.expiryEpoch(base, BackendDefaults.retentionDaysFor(s.getCategory()));
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
        s.setDeleted(getBool(item, "deleted"));
        if (item.containsKey("ttl")) s.setTtl(Long.parseLong(item.get("ttl").n()));
        return s;
    }

    private static void putS(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) item.put(key, AttributeValue.builder().s(value).build());
    }

    private static void putN(Map<String, AttributeValue> item, String key, Long value) {
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

    private static Boolean getBool(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).bool() : null;
    }
}
