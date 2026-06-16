package com.example.livetranscription.interviews;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.GetItemRequest;
import software.amazon.awssdk.services.dynamodb.model.PutItemRequest;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * DynamoDB store for the per-candidate personalized top-up (table
 * {@code personalized_question_cache}, primary key {@code cacheKey}). The key is
 * {date|sha256(resume+keywords+difficulty+category)} so the same candidate
 * restarting the same day reuses their ~10 resume/keyword questions for free.
 *
 * <p>Mirrors the low-level-SDK pattern of {@link InterviewSessionStore}.
 */
@Service
public class PersonalizedQuestionCacheStore {

    private static final String TABLE = "personalized_question_cache";

    private final DynamoDbClient dynamoDbClient;

    public PersonalizedQuestionCacheStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /** Returns the cached top-up JSON, or null on a miss. */
    public String get(String cacheKey) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("cacheKey", AttributeValue.builder().s(cacheKey).build()))
                .build()).item();
        if (item == null || item.isEmpty()) return null;
        return item.containsKey("qaListJson") ? item.get("qaListJson").s() : null;
    }

    public void save(String cacheKey, String date, String qaListJson, int count) {
        long ttl = Instant.now().getEpochSecond()
                + BackendDefaults.PERSONALIZED_CACHE_RETENTION_DAYS * 24L * 3600L;

        Map<String, AttributeValue> item = new HashMap<>();
        item.put("cacheKey", AttributeValue.builder().s(cacheKey).build());
        if (date != null) item.put("date", AttributeValue.builder().s(date).build());
        if (qaListJson != null) item.put("qaListJson", AttributeValue.builder().s(qaListJson).build());
        item.put("count", AttributeValue.builder().n(Integer.toString(count)).build());
        item.put("generatedAt", AttributeValue.builder().s(Instant.now().toString()).build());
        item.put("ttl", AttributeValue.builder().n(Long.toString(ttl)).build());

        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }
}
