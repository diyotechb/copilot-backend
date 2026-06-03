package com.example.livetranscription.dynamodb;

import com.example.livetranscription.model.LiveAssistMessage;
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

    private static final String MESSAGE_TABLE = "live_assist_messages";

    @Override
    public void saveMessage(LiveAssistMessage message) {
        Map<String, AttributeValue> item = new HashMap<>();
        item.put("sessionId", AttributeValue.builder().s(message.getSessionId()).build());
        item.put("timestamp", AttributeValue.builder().s(message.getTimestamp().toString()).build());
        item.put("role", AttributeValue.builder().s(message.getRole()).build());
        item.put("content", AttributeValue.builder().s(message.getContent()).build());
        dynamoDbClient.putItem(PutItemRequest.builder().tableName(MESSAGE_TABLE).item(item).build());
    }

    @Override
    public List<LiveAssistMessage> getMessages(String sessionId) {
        List<Map<String, AttributeValue>> items = dynamoDbClient.query(
                QueryRequest.builder()
                        .tableName(MESSAGE_TABLE)
                        .keyConditionExpression("sessionId = :sid")
                        .expressionAttributeValues(Map.of(":sid", AttributeValue.builder().s(sessionId).build()))
                        .build()).items();
        List<LiveAssistMessage> messages = new ArrayList<>();
        for (Map<String, AttributeValue> item : items) {
            LiveAssistMessage msg = new LiveAssistMessage();
            msg.setSessionId(item.get("sessionId").s());
            msg.setTimestamp(Instant.parse(item.get("timestamp").s()));
            msg.setRole(item.get("role").s());
            msg.setContent(item.get("content").s());
            messages.add(msg);
        }
        return messages;
    }

    @Override
    public void deleteMessage(String sessionId, java.time.Instant timestamp) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(MESSAGE_TABLE)
                .key(Map.of(
                        "sessionId", AttributeValue.builder().s(sessionId).build(),
                        "timestamp", AttributeValue.builder().s(timestamp.toString()).build()))
                .build());
    }
}
