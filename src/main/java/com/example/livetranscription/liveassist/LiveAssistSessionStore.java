package com.example.livetranscription.liveassist;

import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class LiveAssistSessionStore {

    private static final String TABLE = "live_assist_sessions";

    private final DynamoDbClient dynamoDbClient;

    public LiveAssistSessionStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    public void save(LiveAssistSession s) {
        s.setUpdatedAt(Instant.now());
        Map<String, AttributeValue> item = new HashMap<>();
        putS(item, "sessionId", s.getSessionId());
        putS(item, "conversationId", s.getConversationId());
        putS(item, "status", s.getStatus());
        putS(item, "createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        putS(item, "updatedAt", s.getUpdatedAt().toString());
        putS(item, "label", s.getLabel());
        putS(item, "category", s.getCategory());
        putS(item, "resumeText", s.getResumeText());
        putS(item, "finalReport", s.getFinalReport());
        putS(item, "candidateName", s.getCandidateName());
        putS(item, "enrollmentId", s.getEnrollmentId());
        putS(item, "task", s.getTask());
        putS(item, "interviewDateTime", s.getInterviewDateTime());
        putS(item, "client", s.getClient());
        putS(item, "callTaker", s.getCallTaker());
        putS(item, "vendor", s.getVendor());
        putS(item, "duration", s.getDuration());
        putS(item, "outcome", s.getOutcome());
        putS(item, "jobDescription", s.getJobDescription());
        putS(item, "notes", s.getNotes());
        putS(item, "createdBy", s.getCreatedBy());
        putS(item, "updatedBy", s.getUpdatedBy());
        putS(item, "createdByEmail", s.getCreatedByEmail());
        putS(item, "updatedByEmail", s.getUpdatedByEmail());
        long retentionDays = "CANDIDATE".equalsIgnoreCase(s.getCategory()) ? 180 : 60;
        Instant base = s.getCreatedAt() != null ? s.getCreatedAt() : s.getUpdatedAt();
        long ttl = base.plusSeconds(retentionDays * 24L * 3600L).getEpochSecond();
        s.setTtl(ttl);
        item.put("ttl", AttributeValue.builder().n(Long.toString(ttl)).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }

    public LiveAssistSession get(String sessionId) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build()).item();
        if (item == null || item.isEmpty()) return null;
        return toSession(item);
    }

    public List<LiveAssistSession> listAll() {
        List<Map<String, AttributeValue>> items = dynamoDbClient.scan(
                ScanRequest.builder().tableName(TABLE).build()).items();
        List<LiveAssistSession> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) out.add(toSession(item));
        return out;
    }

    public void delete(String sessionId) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .build());
    }

    private LiveAssistSession toSession(Map<String, AttributeValue> item) {
        LiveAssistSession s = new LiveAssistSession();
        s.setSessionId(getS(item, "sessionId"));
        s.setConversationId(getS(item, "conversationId"));
        s.setStatus(getS(item, "status"));
        String createdAt = getS(item, "createdAt");
        if (createdAt != null) s.setCreatedAt(Instant.parse(createdAt));
        String updatedAt = getS(item, "updatedAt");
        if (updatedAt != null) s.setUpdatedAt(Instant.parse(updatedAt));
        s.setLabel(getS(item, "label"));
        s.setCategory(getS(item, "category"));
        s.setResumeText(getS(item, "resumeText"));
        s.setFinalReport(getS(item, "finalReport"));
        s.setCandidateName(getS(item, "candidateName"));
        s.setEnrollmentId(getS(item, "enrollmentId"));
        s.setTask(getS(item, "task"));
        s.setInterviewDateTime(getS(item, "interviewDateTime"));
        s.setClient(getS(item, "client"));
        s.setCallTaker(getS(item, "callTaker"));
        s.setVendor(getS(item, "vendor"));
        s.setDuration(getS(item, "duration"));
        s.setOutcome(getS(item, "outcome"));
        s.setJobDescription(getS(item, "jobDescription"));
        s.setNotes(getS(item, "notes"));
        s.setCreatedBy(getS(item, "createdBy"));
        s.setUpdatedBy(getS(item, "updatedBy"));
        s.setCreatedByEmail(getS(item, "createdByEmail"));
        s.setUpdatedByEmail(getS(item, "updatedByEmail"));
        if (item.containsKey("ttl")) s.setTtl(Long.parseLong(item.get("ttl").n()));
        return s;
    }

    private static void putS(Map<String, AttributeValue> item, String key, String value) {
        if (value != null) item.put(key, AttributeValue.builder().s(value).build());
    }

    private static String getS(Map<String, AttributeValue> item, String key) {
        return item.containsKey(key) ? item.get(key).s() : null;
    }
}
