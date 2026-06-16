package com.example.livetranscription.service.openai;

import com.example.livetranscription.service.openai.InterviewGenerationService.QA;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * In-process cache of the shared daily question banks, keyed by {@code bankKey}
 * ({date|category|difficulty}), so repeated reads on a replica don't re-hit
 * DynamoDB. Per-instance, like {@link TtsAudioCache}: each replica warms its own
 * copy. Entries are bounded by size and a generous write TTL; freshness comes from
 * the date in the key, so a new day simply uses a new key and old entries age out.
 */
@Component
public class QuestionBankCache {

    private final Cache<String, List<QA>> cache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(12))
            .maximumSize(64)
            .recordStats()
            .build();

    public List<QA> getIfPresent(String bankKey) {
        return cache.getIfPresent(bankKey);
    }

    public void put(String bankKey, List<QA> pool) {
        if (pool != null && !pool.isEmpty()) cache.put(bankKey, pool);
    }

    public CacheStats stats() {
        return cache.stats();
    }

    public long estimatedSize() {
        return cache.estimatedSize();
    }
}
