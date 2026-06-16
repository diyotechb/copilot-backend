package com.example.livetranscription.interviews;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * DynamoDB store for the shared daily question bank (table {@code daily_question_bank},
 * primary key {@code bankKey}). One row per {date|category|difficulty} holds the
 * generic question pool reused by every candidate of that type for the day.
 *
 * <p>Follows the low-level-SDK pattern of {@link InterviewSessionStore}. A row is
 * first written as a {@code BUILDING} lock via a conditional put so only one replica
 * builds a given bucket; the winner overwrites it with {@code READY} + the questions.
 */
@Service
public class DailyQuestionBankStore {

    private static final String TABLE = "daily_question_bank";
    public static final String STATUS_BUILDING = "BUILDING";
    public static final String STATUS_READY = "READY";

    private final DynamoDbClient dynamoDbClient;

    public DailyQuestionBankStore(DynamoDbClient dynamoDbClient) {
        this.dynamoDbClient = dynamoDbClient;
    }

    /**
     * Try to claim the right to build this bucket. Succeeds when no row exists yet
     * or when an existing BUILDING lock has expired (a crashed build), so a dead
     * lock can't wedge a bucket for the whole day. Returns false when another
     * replica currently holds a live lock or the bank is already built.
     */
    public boolean acquireBuildLock(String bankKey, String date, String category, String difficulty) {
        long now = Instant.now().getEpochSecond();
        long lockExpiresAt = now + BackendDefaults.BANK_BUILD_LOCK_TTL_SECONDS;
        long ttl = now + BackendDefaults.QUESTION_BANK_RETENTION_DAYS * 24L * 3600L;

        Map<String, AttributeValue> item = new HashMap<>();
        putS(item, "bankKey", bankKey);
        putS(item, "status", STATUS_BUILDING);
        putS(item, "date", date);
        putS(item, "category", category);
        putS(item, "difficulty", difficulty);
        putN(item, "lockExpiresAt", lockExpiresAt);
        putN(item, "ttl", ttl);

        try {
            dynamoDbClient.putItem(PutItemRequest.builder()
                    .tableName(TABLE)
                    .item(item)
                    .conditionExpression("attribute_not_exists(bankKey) OR lockExpiresAt < :now")
                    .expressionAttributeValues(Map.of(
                            ":now", AttributeValue.builder().n(Long.toString(now)).build()))
                    .build());
            return true;
        } catch (ConditionalCheckFailedException e) {
            return false;
        }
    }

    /** Overwrite the BUILDING lock with the finished, READY bank. */
    public void markReady(String bankKey, String date, String category, String difficulty,
                          String qaListJson, int count) {
        long now = Instant.now().getEpochSecond();
        long ttl = now + BackendDefaults.QUESTION_BANK_RETENTION_DAYS * 24L * 3600L;

        Map<String, AttributeValue> item = new HashMap<>();
        putS(item, "bankKey", bankKey);
        putS(item, "status", STATUS_READY);
        putS(item, "date", date);
        putS(item, "category", category);
        putS(item, "difficulty", difficulty);
        putS(item, "qaListJson", qaListJson);
        putN(item, "count", (long) count);
        putS(item, "generatedAt", Instant.now().toString());
        putN(item, "ttl", ttl);

        dynamoDbClient.putItem(PutItemRequest.builder().tableName(TABLE).item(item).build());
    }

    /** Remove a lock we acquired but failed to build, so another replica can retry. */
    public void releaseLock(String bankKey) {
        dynamoDbClient.deleteItem(DeleteItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("bankKey", AttributeValue.builder().s(bankKey).build()))
                .conditionExpression("#st = :building")
                .expressionAttributeNames(Map.of("#st", "status"))
                .expressionAttributeValues(Map.of(":building", AttributeValue.builder().s(STATUS_BUILDING).build()))
                .build());
    }

    public Bank get(String bankKey) {
        Map<String, AttributeValue> item = dynamoDbClient.getItem(GetItemRequest.builder()
                .tableName(TABLE)
                .key(Map.of("bankKey", AttributeValue.builder().s(bankKey).build()))
                .build()).item();
        if (item == null || item.isEmpty()) return null;
        return toBank(item);
    }

    /** Count of READY banks built for a given date (admin metric). */
    public int countReadyForDate(String date) {
        ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE)
                .filterExpression("#d = :d AND #st = :ready")
                .expressionAttributeNames(Map.of("#d", "date", "#st", "status"))
                .expressionAttributeValues(Map.of(
                        ":d", AttributeValue.builder().s(date).build(),
                        ":ready", AttributeValue.builder().s(STATUS_READY).build()))
                .select(Select.COUNT)
                .build());
        return resp.count();
    }

    /** All banks for a date (admin per-type detail view). */
    public List<Bank> listForDate(String date) {
        ScanResponse resp = dynamoDbClient.scan(ScanRequest.builder()
                .tableName(TABLE)
                .filterExpression("#d = :d")
                .expressionAttributeNames(Map.of("#d", "date"))
                .expressionAttributeValues(Map.of(":d", AttributeValue.builder().s(date).build()))
                .build());
        List<Bank> out = new ArrayList<>();
        for (Map<String, AttributeValue> item : resp.items()) out.add(toBank(item));
        return out;
    }

    private Bank toBank(Map<String, AttributeValue> item) {
        Bank b = new Bank();
        b.bankKey = getS(item, "bankKey");
        b.status = getS(item, "status");
        b.date = getS(item, "date");
        b.category = getS(item, "category");
        b.difficulty = getS(item, "difficulty");
        b.qaListJson = getS(item, "qaListJson");
        Long count = getN(item, "count");
        b.count = count != null ? count.intValue() : 0;
        b.generatedAt = getS(item, "generatedAt");
        return b;
    }

    public static final class Bank {
        public String bankKey;
        public String status;
        public String date;
        public String category;
        public String difficulty;
        public String qaListJson;
        public int count;
        public String generatedAt;

        public boolean isReady() { return STATUS_READY.equals(status); }
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
