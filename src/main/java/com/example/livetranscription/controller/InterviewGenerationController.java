package com.example.livetranscription.controller;

import com.example.livetranscription.service.openai.InterviewGenerationService;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerateRequest;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerationListener;
import com.example.livetranscription.service.openai.InterviewGenerationService.QA;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/api/interview")
public class InterviewGenerationController {

    private static final Logger log = LoggerFactory.getLogger(InterviewGenerationController.class);

    // SseEmitter's 30s default trips on slow OpenAI runs; generation can take a few minutes.
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;
    // Keepalive interval — short enough that proxies (CloudFront, ALB) don't kill an idle stream.
    private static final long HEARTBEAT_INTERVAL_SEC = 15L;

    // Bounded: each generate spawns 18 in-flight OpenAI calls. On a t3.micro
    // ~8 concurrent generates is the safe ceiling before heap pressure bites.
    // Queue absorbs short bursts up to 16; beyond that CallerRunsPolicy makes
    // the Tomcat thread do the work itself, slowing the 25th+ request
    // instead of letting the JVM OOM.
    private static final int CORE_POOL = 2;
    private static final int MAX_POOL = 8;
    private static final int QUEUE_CAPACITY = 16;
    private static final long IDLE_KEEPALIVE_SEC = 60L;

    private final InterviewGenerationService service;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats;

    public InterviewGenerationController(InterviewGenerationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
        this.executor = buildGenerationExecutor();
        this.heartbeats = buildHeartbeatScheduler();
    }

    private static ExecutorService buildGenerationExecutor() {
        AtomicLong idx = new AtomicLong();
        ThreadPoolExecutor exec = new ThreadPoolExecutor(
                CORE_POOL,
                MAX_POOL,
                IDLE_KEEPALIVE_SEC, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(QUEUE_CAPACITY),
                r -> {
                    Thread t = new Thread(r, "interview-gen-" + idx.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.CallerRunsPolicy());
        exec.allowCoreThreadTimeOut(true);
        return exec;
    }

    private static ScheduledExecutorService buildHeartbeatScheduler() {
        AtomicLong idx = new AtomicLong();
        // Two threads so one slow emitter.send doesn't block heartbeats on
        // other live SSE streams.
        return Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "interview-heartbeat-" + idx.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @PostMapping(value = "/generate", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter generate(@Valid @RequestBody GenerateRequest req) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);

        ScheduledFuture<?> heartbeat = heartbeats.scheduleAtFixedRate(() -> {
            try {
                emitter.send(SseEmitter.event().comment("keepalive"));
            } catch (Exception e) {
                // Client disconnected or emitter completed — stop trying.
            }
        }, HEARTBEAT_INTERVAL_SEC, HEARTBEAT_INTERVAL_SEC, TimeUnit.SECONDS);

        emitter.onCompletion(() -> heartbeat.cancel(false));
        emitter.onTimeout(() -> { heartbeat.cancel(false); emitter.complete(); });
        emitter.onError(t -> heartbeat.cancel(false));

        executor.submit(() -> {
            GenerationListener listener = new GenerationListener() {
                @Override
                public void onProgress(int ready, int target, boolean firstBatchDone) {
                    sendEvent(emitter, "progress", Map.of(
                            "ready", ready,
                            "target", target,
                            "firstBatchDone", firstBatchDone
                    ));
                }

                @Override
                public void onUpdate(List<QA> assembled) {
                    sendEvent(emitter, "update", InterviewGenerationService.toClientShape(assembled));
                }

                @Override
                public void onDone(List<QA> assembled) {
                    sendEvent(emitter, "done", InterviewGenerationService.toClientShape(assembled));
                    emitter.complete();
                }

                @Override
                public void onError(Throwable t) {
                    log.warn("interview_generate_failed error={}", t.getMessage());
                    sendEvent(emitter, "error", Map.of("message", t.getMessage() == null ? "Unknown error" : t.getMessage()));
                    emitter.complete();
                }
            };

            try {
                service.generate(req, listener);
            } catch (Throwable t) {
                listener.onError(t);
            }
        });

        return emitter;
    }

    private void sendEvent(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    @PreDestroy
    public void shutdown() {
        shutdownPool(executor, "interview-gen");
        shutdownPool(heartbeats, "interview-heartbeat");
    }

    private static void shutdownPool(ExecutorService pool, String name) {
        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("{} pool did not terminate cleanly, forcing shutdown", name);
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
