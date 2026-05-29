package com.example.livetranscription.voice;

import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.InterviewSession;
import com.example.livetranscription.voice.InterviewConversationContext.QaPair;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// Path is /ws/interview-voice/** (outside /api/**) so it inherits SecurityConfig's permitAll rule, matching the WS endpoint.
@RestController
@RequestMapping("/ws/interview-voice")
public class InterviewSessionController {

    private final InterviewContextStore contextStore;
    private final DynamoPersistenceService persistence;

    public InterviewSessionController(InterviewContextStore contextStore,
                                      DynamoPersistenceService persistence) {
        this.contextStore = contextStore;
        this.persistence = persistence;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> buildSession(@RequestBody BuildSessionRequest req) {
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "conversationId is required"));
        }

        InterviewConversationContext ctx = contextStore.getOrCreate(req.conversationId());
        ctx.buildSession(req.resumeText(), req.pastQAs());

        String sessionId = (req.sessionId() != null && !req.sessionId().isBlank())
                ? req.sessionId()
                : UUID.randomUUID().toString();
        ctx.setStorageSessionId(sessionId);

        InterviewSession session = new InterviewSession();
        session.setSessionId(sessionId);
        session.setConversationId(req.conversationId());
        session.setCandidateId(req.conversationId());
        session.setStatus("ACTIVE");
        session.setCreatedAt(Instant.now());
        session.setTtl(Instant.now().plusSeconds(7 * 24 * 3600).getEpochSecond());
        persistence.saveSession(session);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "conversationId", req.conversationId(),
                "sessionId", sessionId,
                "sessionBuilt", true
        ));
    }

    @PostMapping("/session/complete")
    public ResponseEntity<Map<String, Object>> completeSession(@RequestBody CompleteSessionRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "sessionId is required"));
        }

        InterviewSession session = persistence.getSession(req.sessionId());
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        session.setStatus("COMPLETED");
        persistence.saveSession(session);

        return ResponseEntity.ok(Map.of("ok", true, "sessionId", req.sessionId(), "status", "COMPLETED"));
    }

    public record BuildSessionRequest(
            String conversationId,
            String sessionId,
            String resumeText,
            List<QaPair> pastQAs
    ) {}

    public record CompleteSessionRequest(String sessionId) {}
}
