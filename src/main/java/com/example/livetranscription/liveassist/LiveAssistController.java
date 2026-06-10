package com.example.livetranscription.liveassist;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.config.RoleGroups;
import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.LiveAssistMessage;
import com.example.livetranscription.liveassist.LiveAssistConversationContext.QaPair;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final Set<String> STAFF_AUTHORITIES =
            Arrays.stream(RoleGroups.STAFF).map(r -> "ROLE_" + r).collect(Collectors.toSet());

    private String caller() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.isAuthenticated()) ? a.getName() : null;
    }

    private boolean unauthenticated() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated();
    }

    private boolean isStaff() {
        if (unauthenticated()) return true;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF_AUTHORITIES::contains);
    }

    private boolean isOwner(LiveAssistSession s) {
        if (unauthenticated()) return true;
        String c = caller();
        return c != null && c.equals(s.getCreatedBy());
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
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
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

        boolean isCandidate = req.enrollmentId() != null && !req.enrollmentId().isBlank();
        String category = isCandidate ? "CANDIDATE" : "NONE";
        ctx.setRetentionDays(isCandidate ? 180 : 60);

        LiveAssistSession session = new LiveAssistSession();
        session.setSessionId(sessionId);
        session.setConversationId(req.conversationId());
        session.setStatus("ACTIVE");
        session.setCategory(category);
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
        session.setCreatedBy(caller());
        session.setUpdatedBy(caller());
        session.setCreatedByEmail(req.createdByEmail());
        session.setUpdatedByEmail(req.createdByEmail());
        session.setCreatedAt(Instant.now());
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
        if (!isOwner(session)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "only the creator can continue this session"));
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

        boolean isCandidate = "CANDIDATE".equalsIgnoreCase(session.getCategory())
                || (session.getEnrollmentId() != null && !session.getEnrollmentId().isBlank());
        ctx.setRetentionDays(isCandidate ? 180 : 60);
        if (session.getCategory() == null || session.getCategory().isBlank()) {
            session.setCategory(isCandidate ? "CANDIDATE" : "NONE");
        }

        session.setConversationId(req.conversationId());
        session.setStatus("ACTIVE");
        session.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) session.setUpdatedByEmail(req.updatedByEmail());
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
        if (!isOwner(session)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "only the creator can end this session"));
        }
        if (!"COMPLETED".equals(session.getStatus())) {
            session.setStatus("ENDED");
            session.setUpdatedBy(caller());
            if (req.updatedByEmail() != null) session.setUpdatedByEmail(req.updatedByEmail());
            sessionStore.save(session);
        }

        return ResponseEntity.ok(Map.of("ok", true, "sessionId", req.sessionId(), "status", session.getStatus()));
    }

    @PostMapping("/session/complete")
    public ResponseEntity<Map<String, Object>> completeSession(@RequestBody CompleteSessionRequest req) {
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "only staff can analyze a session"));
        }
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
        session.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) session.setUpdatedByEmail(req.updatedByEmail());
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
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        session.setOutcome(req.outcome());
        session.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) session.setUpdatedByEmail(req.updatedByEmail());
        sessionStore.save(session);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "outcome", req.outcome() == null ? "" : req.outcome()));
    }

    @PostMapping("/session/{sessionId}/rename")
    public ResponseEntity<Map<String, Object>> renameSession(@PathVariable String sessionId,
                                                             @RequestBody RenameRequest req) {
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
        if (req.label() == null || req.label().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "label is required"));
        }
        LiveAssistSession session = sessionStore.get(sessionId);
        if (session == null) {
            return ResponseEntity.ok(Map.of("ok", false, "error", "session not found"));
        }
        session.setLabel(req.label().trim());
        session.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) session.setUpdatedByEmail(req.updatedByEmail());
        sessionStore.save(session);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId, "label", session.getLabel()));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> listSessions() {
        if (!isStaff()) {
            return ResponseEntity.status(403).body(List.of());
        }
        List<LiveAssistSession> sessions = sessionStore.listAll();
        sessions.sort(Comparator.comparing(LiveAssistSession::getCreatedAt,
                Comparator.nullsLast(Comparator.reverseOrder())));
        List<Map<String, Object>> out = new ArrayList<>();
        for (LiveAssistSession s : sessions) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("conversationId", s.getConversationId());
            m.put("label", s.getLabel());
            m.put("category", s.getCategory());
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
            m.put("createdBy", s.getCreatedBy());
            m.put("createdByEmail", s.getCreatedByEmail());
            m.put("updatedBy", s.getUpdatedBy());
            m.put("updatedByEmail", s.getUpdatedByEmail());
            m.put("isOwner", isOwner(s));
            m.put("preview", buildPreview(s.getSessionId()));
            out.add(m);
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSession(@PathVariable String sessionId) {
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
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
        out.put("createdBy", session.getCreatedBy());
        out.put("createdByEmail", session.getCreatedByEmail());
        out.put("updatedBy", session.getUpdatedBy());
        out.put("updatedByEmail", session.getUpdatedByEmail());
        out.put("isOwner", isOwner(session));
        out.put("messages", toQa(persistence.getMessages(sessionId)));
        return ResponseEntity.ok(out);
    }

    @DeleteMapping("/session/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        if (!isStaff()) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
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
            String createdByEmail,
            List<QaPair> pastQAs
    ) {}

    public record CompleteSessionRequest(String sessionId, String updatedByEmail) {}

    public record OutcomeRequest(String outcome, String updatedByEmail) {}

    public record RenameRequest(String label, String updatedByEmail) {}

    public record ContinueSessionRequest(String conversationId, String updatedByEmail) {}
}
