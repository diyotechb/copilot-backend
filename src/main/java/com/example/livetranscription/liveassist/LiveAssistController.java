package com.example.livetranscription.liveassist;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.LiveAssistMessage;
import com.example.livetranscription.liveassist.LiveAssistConversationContext.QaPair;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/ws/live-assist")
public class LiveAssistController {

    private final LiveAssistContextStore contextStore;
    private final LiveAssistSessionStore sessionStore;
    private final CandidateResumeStore resumeStore;
    private final DynamoPersistenceService persistence;
    private final LiveAssistSummaryService summaryService;

    public LiveAssistController(LiveAssistContextStore contextStore,
                                      LiveAssistSessionStore sessionStore,
                                      CandidateResumeStore resumeStore,
                                      DynamoPersistenceService persistence,
                                      LiveAssistSummaryService summaryService) {
        this.contextStore = contextStore;
        this.sessionStore = sessionStore;
        this.resumeStore = resumeStore;
        this.persistence = persistence;
        this.summaryService = summaryService;
    }

    @GetMapping("/resume/{enrollmentId}")
    public ResponseEntity<Map<String, Object>> getCandidateResume(@PathVariable String enrollmentId) {
        String resumeText = resumeStore.get(enrollmentId);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("enrollmentId", enrollmentId);
        out.put("resumeText", resumeText);
        return ResponseEntity.ok(out);
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> buildSession(@RequestBody BuildSessionRequest req) {
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "conversationId is required"));
        }
        if (req.resumeText() != null && req.resumeText().length() > BackendDefaults.MAX_RESUME_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "resume exceeds maximum length of " + BackendDefaults.MAX_RESUME_CHARS + " characters"));
        }
        if (req.label() != null && req.label().length() > BackendDefaults.MAX_SESSION_LABEL_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "session name exceeds maximum length of " + BackendDefaults.MAX_SESSION_LABEL_CHARS + " characters"));
        }
        if (req.jobDescription() != null && req.jobDescription().length() > BackendDefaults.MAX_JOB_DESCRIPTION_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "job description exceeds maximum length of " + BackendDefaults.MAX_JOB_DESCRIPTION_CHARS + " characters"));
        }
        if (req.notes() != null && req.notes().length() > BackendDefaults.MAX_SESSION_NOTES_CHARS) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "notes exceed maximum length of " + BackendDefaults.MAX_SESSION_NOTES_CHARS + " characters"));
        }

        LiveAssistConversationContext ctx = contextStore.getOrCreate(req.conversationId());
        ctx.buildSession(req.resumeText(), req.jobDescription(), req.notes(), req.pastQAs());

        String sessionId = (req.sessionId() != null && !req.sessionId().isBlank())
                ? req.sessionId()
                : UUID.randomUUID().toString();
        ctx.setStorageSessionId(sessionId);

        LiveAssistSession session = new LiveAssistSession();
        session.setSessionId(sessionId);
        session.setConversationId(req.conversationId());
        session.setStatus("ACTIVE");
        session.setResumeText(req.resumeText());
        session.setLabel(req.label());
        session.setCandidateName(req.candidateName());
        session.setEnrollmentId(req.enrollmentId());
        session.setTask(req.task());
        session.setInterviewDateTime(req.interviewDateTime());
        session.setClient(req.client());
        session.setCallTaker(req.callTaker());
        session.setVendor(req.vendor());
        session.setDuration(req.duration());
        session.setOutcome(req.outcome());
        session.setJobDescription(req.jobDescription());
        session.setNotes(req.notes());
        session.setCreatedAt(Instant.now());
        session.setTtl(Instant.now().plusSeconds(7 * 24 * 3600).getEpochSecond());
        sessionStore.save(session);
        resumeStore.save(req.enrollmentId(), req.resumeText());

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "conversationId", req.conversationId(),
                "sessionId", sessionId,
                "sessionBuilt", true
        ));
    }

    @PostMapping("/session/{sessionId}/continue")
    public ResponseEntity<Map<String, Object>> continueSession(@PathVariable String sessionId,
                                                               @RequestBody ContinueSessionRequest req) {
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        if (req.conversationId() == null || req.conversationId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "conversationId is required"));
        }

        List<LiveAssistMessage> messages = persistence.getMessages(sessionId);
        List<QaPair> prior = new ArrayList<>();
        for (int i = 0; i < messages.size() - 1; i++) {
            LiveAssistMessage q = messages.get(i);
            LiveAssistMessage a = messages.get(i + 1);
            if ("user".equals(q.getRole()) && "assistant".equals(a.getRole())) {
                prior.add(new QaPair(q.getContent(), a.getContent()));
                i++;
            }
        }

        LiveAssistConversationContext ctx = contextStore.getOrCreate(req.conversationId());
        ctx.continueSession(session.getResumeText(), session.getJobDescription(), session.getNotes(), null, prior);
        ctx.setStorageSessionId(sessionId);

        session.setConversationId(req.conversationId());
        session.setStatus("ACTIVE");
        sessionStore.save(session);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", sessionId);
        out.put("conversationId", req.conversationId());
        out.put("resumeText", session.getResumeText());
        out.put("label", session.getLabel());
        out.put("messages", toQa(messages));
        return ResponseEntity.ok(out);
    }

    @PostMapping("/session/end")
    public ResponseEntity<Map<String, Object>> endSession(@RequestBody CompleteSessionRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "sessionId is required"));
        }

        LiveAssistSession session = sessionStore.get(req.sessionId());
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        if (!"COMPLETED".equals(session.getStatus())) {
            session.setStatus("ENDED");
            sessionStore.save(session);
        }

        return ResponseEntity.ok(Map.of("ok", true, "sessionId", req.sessionId(), "status", session.getStatus()));
    }

    @PostMapping("/session/complete")
    public ResponseEntity<Map<String, Object>> completeSession(@RequestBody CompleteSessionRequest req) {
        if (req.sessionId() == null || req.sessionId().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("ok", false, "error", "sessionId is required"));
        }

        LiveAssistSession session = sessionStore.get(req.sessionId());
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }

        String summaryJson = summaryService.generateSummary(req.sessionId());
        session.setStatus("COMPLETED");
        session.setFinalReport(summaryJson);
        sessionStore.save(session);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "sessionId", req.sessionId(),
                "status", "COMPLETED",
                "summary", summaryJson
        ));
    }

    @PostMapping("/session/{sessionId}/outcome")
    public ResponseEntity<Map<String, Object>> setOutcome(@PathVariable String sessionId,
                                                          @RequestBody OutcomeRequest req) {
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        session.setOutcome(req.outcome());
        sessionStore.save(session);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "outcome", req.outcome() == null ? "" : req.outcome()));
    }

    @PostMapping("/session/{sessionId}/rename")
    public ResponseEntity<Map<String, Object>> renameSession(@PathVariable String sessionId,
                                                             @RequestBody RenameRequest req) {
        if (req.label() == null || req.label().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "label is required"));
        }
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        session.setLabel(req.label().trim());
        sessionStore.save(session);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "label", session.getLabel()));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        List<LiveAssistSession> sessions = sessionStore.listAll();
        sessions.sort(Comparator.comparing(LiveAssistSession::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveAssistSession s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("conversationId", s.getConversationId());
            m.put("label", s.getLabel());
            m.put("candidateName", s.getCandidateName());
            m.put("status", s.getStatus());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
            m.put("enrollmentId", s.getEnrollmentId());
            m.put("task", s.getTask());
            m.put("interviewDateTime", s.getInterviewDateTime());
            m.put("client", s.getClient());
            m.put("callTaker", s.getCallTaker());
            m.put("vendor", s.getVendor());
            m.put("duration", s.getDuration());
            m.put("outcome", s.getOutcome());
            m.put("hasSummary", s.getFinalReport() != null && !s.getFinalReport().isBlank());
            m.put("preview", buildPreview(s.getSessionId()));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", session.getSessionId());
        out.put("conversationId", session.getConversationId());
        out.put("label", session.getLabel());
        out.put("candidateName", session.getCandidateName());
        out.put("resumeText", session.getResumeText());
        out.put("status", session.getStatus());
        out.put("createdAt", session.getCreatedAt() != null ? session.getCreatedAt().toString() : null);
        out.put("updatedAt", session.getUpdatedAt() != null ? session.getUpdatedAt().toString() : null);
        out.put("enrollmentId", session.getEnrollmentId());
        out.put("task", session.getTask());
        out.put("interviewDateTime", session.getInterviewDateTime());
        out.put("client", session.getClient());
        out.put("callTaker", session.getCallTaker());
        out.put("vendor", session.getVendor());
        out.put("duration", session.getDuration());
        out.put("outcome", session.getOutcome());
        out.put("jobDescription", session.getJobDescription());
        out.put("notes", session.getNotes());
        out.put("summary", session.getFinalReport());
        out.put("messages", toQa(persistence.getMessages(sessionId)));
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        for (LiveAssistMessage msg : persistence.getMessages(sessionId)) {
            persistence.deleteMessage(sessionId, msg.getTimestamp());
        }
        sessionStore.delete(sessionId);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId));
    }

    private List<Map<String, Object>> toQa(List<LiveAssistMessage> messages) {
        List<Map<String, Object>> qa = new ArrayList<>();
        for (LiveAssistMessage msg : messages) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("role", msg.getRole());
            m.put("content", msg.getContent());
            m.put("timestamp", msg.getTimestamp() != null ? msg.getTimestamp().toString() : null);
            qa.add(m);
        }
        return qa;
    }

    private String buildPreview(String sessionId) {
        StringBuilder sb = new StringBuilder();
        for (LiveAssistMessage msg : persistence.getMessages(sessionId)) {
            if (!"assistant".equals(msg.getRole())) continue;
            if (msg.getContent() == null || msg.getContent().isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(msg.getContent().trim());
            if (sb.length() > 200) break;
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    public record BuildSessionRequest(
            String conversationId,
            String sessionId,
            String resumeText,
            String label,
            String candidateName,
            String enrollmentId,
            String task,
            String interviewDateTime,
            String client,
            String callTaker,
            String vendor,
            String duration,
            String outcome,
            String jobDescription,
            String notes,
            List<QaPair> pastQAs
    ) {}

    public record CompleteSessionRequest(String sessionId) {}

    public record OutcomeRequest(String outcome) {}

    public record RenameRequest(String label) {}

    public record ContinueSessionRequest(String conversationId) {}
}
