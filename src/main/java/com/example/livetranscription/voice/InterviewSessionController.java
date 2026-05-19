package com.example.livetranscription.voice;

import com.example.livetranscription.voice.InterviewConversationContext.QaPair;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST endpoint that enriches an InterviewConversationContext with the
 * candidate's resume and past successful Q&As before the live session starts.
 *
 * Path is /ws/interview-voice/session — outside /api/** so it inherits
 * SecurityConfig's anyRequest().permitAll() rule (same as the WS endpoint).
 */
@RestController
@RequestMapping("/ws/interview-voice")
public class InterviewSessionController {

    private final InterviewContextStore contextStore;

    public InterviewSessionController(InterviewContextStore contextStore) {
        this.contextStore = contextStore;
    }

    /**
     * Build (or rebuild) a session context for the given conversationId.
     *
     * Request body:
     * {
     *   "conversationId": "diyotech-copilot",
     *   "resumeText":     "Full text of the candidate's resume…",
     *   "pastQAs": [
     *     { "question": "Tell me about yourself", "answer": "…" },
     *     …
     *   ]
     * }
     */
    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> buildSession(@RequestBody BuildSessionRequest req) {
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "conversationId is required"));
        }

        InterviewConversationContext ctx = contextStore.getOrCreate(req.conversationId());
        ctx.buildSession(req.resumeText(), req.pastQAs());

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "conversationId", req.conversationId(),
                "sessionBuilt", true
        ));
    }

    public record BuildSessionRequest(
            String conversationId,
            String resumeText,
            List<QaPair> pastQAs
    ) {}
}
