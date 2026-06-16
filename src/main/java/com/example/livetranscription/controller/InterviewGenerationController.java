package com.example.livetranscription.controller;

import com.example.livetranscription.config.RoleGroups;
import com.example.livetranscription.service.openai.CachedQuestionService;
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
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
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

    private static final Set<String> STAFF_AUTHORITIES =
            Arrays.stream(RoleGroups.STAFF).map(r -> "ROLE_" + r).collect(Collectors.toSet());

    private final InterviewGenerationService service;
    private final CachedQuestionService cachedQuestionService;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final ScheduledExecutorService heartbeats;

    public InterviewGenerationController(InterviewGenerationService service,
                                         CachedQuestionService cachedQuestionService,
                                         ObjectMapper mapper) {
        this.service = service;
        this.cachedQuestionService = cachedQuestionService;
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

        // Resolve the staff-only "fully personalized" bypass on the request thread —
        // the SecurityContext is thread-local and won't survive the executor handoff.
        boolean bypassCache = Boolean.TRUE.equals(req.fullyPersonalized()) && isStaff();

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
                    sendEvent(emitter, "error", Map.of("message", SYSTEM_UNAVAILABLE_MSG));
                    emitter.complete();
                }
            };

            try {
                if (bypassCache) {
                    cachedQuestionService.recordFullyPersonalizedBypass();
                    service.generate(req, listener);
                } else {
                    cachedQuestionService.generate(req, listener);
                }
            } catch (Throwable t) {
                listener.onError(t);
            }
        });

        return emitter;
    }

    private static boolean isStaff() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        // dev fallback: unauthenticated requests are treated as staff (matches InterviewController)
        if (a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated()) return true;
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF_AUTHORITIES::contains);
    }

    private static final String SYSTEM_UNAVAILABLE_MSG = "The system is temporarily unavailable. Please try again in a little while.";

    private void sendEvent(SseEmitter emitter, String name, Object payload) {
        try {
            emitter.send(SseEmitter.event()
                    .name(name)
                    .data(mapper.writeValueAsString(payload), MediaType.APPLICATION_JSON));
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
    }

    /** Snapshot of the generate executor for the admin status endpoint. */
    public Map<String, Integer> executorStats() {
        if (executor instanceof ThreadPoolExecutor tpe) {
            return Map.of(
                    "active", tpe.getActiveCount(),
                    "queued", tpe.getQueue().size(),
                    "pool_size", tpe.getPoolSize(),
                    "max_pool", tpe.getMaximumPoolSize(),
                    "queue_capacity", QUEUE_CAPACITY,
                    "completed_total", (int) Math.min(tpe.getCompletedTaskCount(), Integer.MAX_VALUE));
        }
        return Map.of("active", -1, "queued", -1, "pool_size", -1, "max_pool", MAX_POOL,
                "queue_capacity", QUEUE_CAPACITY, "completed_total", -1);
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
