package com.example.livetranscription.controller;

import com.example.livetranscription.service.openai.InterviewGenerationService;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerateRequest;
import com.example.livetranscription.service.openai.InterviewGenerationService.GenerationListener;
import com.example.livetranscription.service.openai.InterviewGenerationService.QA;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/interview")
public class InterviewGenerationController {

    private static final Logger log = LoggerFactory.getLogger(InterviewGenerationController.class);

    // SseEmitter's 30s default trips on slow OpenAI runs; generation can take a few minutes.
    private static final long SSE_TIMEOUT_MS = 5 * 60 * 1000L;
    // Keepalive interval — short enough that proxies (CloudFront, ALB) don't kill an idle stream.
    private static final long HEARTBEAT_INTERVAL_SEC = 15L;

    private final InterviewGenerationService service;
    private final ObjectMapper mapper;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor();

    public InterviewGenerationController(InterviewGenerationService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
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
}
