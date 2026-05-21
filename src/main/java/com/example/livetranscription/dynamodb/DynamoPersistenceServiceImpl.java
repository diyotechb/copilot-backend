package com.example.livetranscription.dynamodb;

import com.example.livetranscription.model.InterviewSession;
import com.example.livetranscription.model.InterviewMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.*;

@Service
public class DynamoPersistenceServiceImpl implements DynamoPersistenceService {

    @Autowired
    private DynamoDbClient dynamoDbClient;

    private static final String SESSION_TABLE = "interview_sessions";
    private static final String MESSAGE_TABLE = "interview_messages";

    @Override
    public void saveSession(InterviewSession session) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sessionId", AttributeValue.builder().s(session.getSessionId()).build());
        item.put("conversationId", AttributeValue.builder().s(session.getConversationId()).build());
        item.put("candidateId", AttributeValue.builder().s(session.getCandidateId()).build());
        item.put("status", AttributeValue.builder().s(session.getStatus()).build());
        item.put("createdAt", AttributeValue.builder().s(session.getCreatedAt().toString()).build());
        if (session.getResumeSummary() != null)
            item.put("resumeSummary", AttributeValue.builder().s(session.getResumeSummary()).build());
        if (session.getTtl() != null)
            item.put("ttl", AttributeValue.builder().n(session.getTtl().toString()).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(SESSION_TABLE).item(item).build());
    }

    @Override
    public InterviewSession getSession(String sessionId) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(
                GetItemRequest.builder()
                        .tableName(SESSION_TABLE)
                        .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                        .build()).item();
        if (item == null || item.isEmpty()) return null;
        InterviewSession session = new InterviewSession();
        session.setSessionId(item.get("sessionId").s());
        session.setConversationId(item.get("conversationId").s());
        session.setCandidateId(item.get("candidateId").s());
        session.setStatus(item.get("status").s());
        session.setCreatedAt(Instant.parse(item.get("createdAt").s()));
        if (item.containsKey("resumeSummary"))
            session.setResumeSummary(item.get("resumeSummary").s());
        if (item.containsKey("ttl"))
            session.setTtl(Long.parseLong(item.get("ttl").n()));
        return session;
    }

    @Override
    public void saveMessage(InterviewMessage message) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sessionId", AttributeValue.builder().s(message.getSessionId()).build());
        item.put("timestamp", AttributeValue.builder().s(message.getTimestamp().toString()).build());
        item.put("role", AttributeValue.builder().s(message.getRole()).build());
        item.put("content", AttributeValue.builder().s(message.getContent()).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(MESSAGE_TABLE).item(item).build());
    }

    @Override
    public List<InterviewMessage> getMessages(String sessionId) {
        List<Map<String, AttributeValue>> items = dynamoDbClient.query(
                QueryRequest.builder()
                        .tableName(MESSAGE_TABLE)
                        .keyConditionExpression("sessionId = :sid")
                        .expressionAttributeValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build()).items();
        List<InterviewMessage> messages = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) {
            InterviewMessage msg = new InterviewMessage();
            msg.setSessionId(item.get("sessionId").s());
            msg.setTimestamp(Instant.parse(item.get("timestamp").s()));
            msg.setRole(item.get("role").s());
            msg.setContent(item.get("content").s());
            messages.add(msg);
        }
        return messages;
    }

    @Override
    public void saveInterviewSummary(String sessionId, String summaryJson) {
        dynamoDbClient.updateItem(UpdateItemRequest.builder()
                .tableName(SESSION_TABLE)
                .key(Map.of("sessionId", AttributeValue.builder().s(sessionId).build()))
                .attributeUpdates(Map.of(
                        "finalReport", AttributeValueUpdate.builder()
                                .value(AttributeValue.builder().s(summaryJson).build())
                                .action(AttributeAction.PUT).build()))
                .build());
    }
}
