package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.function.Supplier;

// In-memory cache of OpenAI TTS audio responses. Keyed by SHA-256 of
// (model|voice|format|text); same inputs always yield the same audio bytes,
// so there is no TTL — entries only leave the cache via weight-based LRU.
// Weight = byte length of the audio so a 200 MB heap budget can hold a mix
// of large and small clips without an arbitrary entry-count guess.
//
// Per-instance only: on a 1-4 instance Elastic Beanstalk fleet each replica
// warms its cache independently. Acceptable at current scale (~600 TTS
// calls/day); upgrade to a shared store (S3 or CloudFront) if usage grows.
@Component
public class TtsAudioCache {

    private static final Logger log = LoggerFactory.getLogger(TtsAudioCache.class);

    private static final long MAX_WEIGHT_BYTES = 200L * 1024L * 1024L;

    private final Cache<String, byte[]> cache = Caffeine.newBuilder()
            .maximumWeight(MAX_WEIGHT_BYTES)
            .<String, byte[]>weigher((k, v) -> v.length)
            .recordStats()
            .build();

    public String key(String voice, String format, String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(BackendDefaults.OPENAI_TTS_MODEL.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(voice.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(format.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '|');
            md.update(text.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public byte[] getOrLoad(String key, Supplier<byte[]> loader) {
        byte[] cached = cache.getIfPresent(key);
        if (cached != null) {
            if (log.isDebugEnabled()) log.debug("tts_cache_hit key={} bytes={}", key, cached.length);
            return cached;
        }
        byte[] fresh = loader.get();
        if (fresh != null && fresh.length > 0) {
            cache.put(key, fresh);
            if (log.isDebugEnabled()) log.debug("tts_cache_miss key={} bytes={}", key, fresh.length);
        }
        return fresh;
    }

    public CacheStats stats() {
        return cache.stats();
    }
}
