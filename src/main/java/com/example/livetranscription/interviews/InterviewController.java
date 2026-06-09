package com.example.livetranscription.interviews;

import com.example.livetranscription.config.RoleGroups;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@RequestMapping("/api/interviews")
public class InterviewController {

    private static final Set<String> STAFF_AUTHORITIES =
            Arrays.stream(RoleGroups.STAFF).map(r -> "ROLE_" + r).collect(Collectors.toSet());

    private final InterviewSessionStore store;
    private final ObjectMapper objectMapper;

    public InterviewController(InterviewSessionStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> save(@RequestBody SaveRequest req) {
        String sessionId = (req.sessionId() != null && !req.sessionId().isBlank())
                ? req.sessionId() : newSessionId();
        InterviewSession existing = store.get(sessionId);
        if (existing != null && !canManage(existing)) {
            return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        }
        String caller = caller();

        InterviewSession s = existing != null ? existing : new InterviewSession();
        if (existing == null) {
            s.setSessionId(sessionId);
            s.setCreatedAt(Instant.now());
            s.setCreatedBy(caller);
            s.setCreatedByEmail(req.createdByEmail());
        }

        if (req.startedAt() != null) s.setStartedAt(req.startedAt());
        if (req.endedAt() != null) s.setEndedAt(req.endedAt());
        if (req.label() != null) s.setLabel(req.label());
        if (req.completed() != null) s.setCompleted(req.completed());
        if (req.candidateName() != null) s.setCandidateName(req.candidateName());
        if (req.enrollmentId() != null) s.setEnrollmentId(req.enrollmentId());
        if (req.difficulty() != null) s.setDifficulty(req.difficulty());
        if (req.category() != null) s.setCategory(req.category());
        if (req.analysisMode() != null) s.setAnalysisMode(req.analysisMode());
        if (req.qaList() != null) {
            s.setQaListJson(serialize(req.qaList()));
            s.setQuestionCount(req.qaList().isArray() ? req.qaList().size() : null);
        }
        if (req.transcripts() != null) s.setTranscriptsJson(serialize(req.transcripts()));
        if (req.questionTimestamps() != null) s.setQuestionTimestampsJson(serialize(req.questionTimestamps()));
        if (req.llmAnalysis() != null) {
            s.setLlmAnalysisJson(serialize(req.llmAnalysis()));
            s.setAvgScore(computeAvgScore(req.llmAnalysis()));
        }

        return ResponseEntity.ok(persist(s, caller, req.createdByEmail()));
    }

    @PatchMapping("/session/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody SaveRequest req) {
        InterviewSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireManage(s);
        if (guard != null) return guard;

        if (req.label() != null) s.setLabel(req.label());
        if (req.completed() != null) s.setCompleted(req.completed());
        if (req.candidateName() != null) s.setCandidateName(req.candidateName());
        if (req.startedAt() != null) s.setStartedAt(req.startedAt());
        if (req.endedAt() != null) s.setEndedAt(req.endedAt());
        if (req.analysisMode() != null) s.setAnalysisMode(req.analysisMode());
        if (req.transcripts() != null) s.setTranscriptsJson(serialize(req.transcripts()));
        if (req.questionTimestamps() != null) s.setQuestionTimestampsJson(serialize(req.questionTimestamps()));
        if (req.llmAnalysis() != null) {
            s.setLlmAnalysisJson(serialize(req.llmAnalysis()));
            s.setAvgScore(computeAvgScore(req.llmAnalysis()));
        }

        return ResponseEntity.ok(persist(s, caller(), req.updatedByEmail()));
    }

    private Map<String, Object> persist(InterviewSession s, String caller, String email) {
        s.setUpdatedBy(caller);
        if (email != null && !email.isBlank()) s.setUpdatedByEmail(email);
        s.setStatus(deriveStatus(s));
        store.save(s);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sessionId", s.getSessionId());
        out.put("id", s.getSessionId());
        return out;
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<Map<String, Object>>> list() {
        boolean staff = isStaff();
        List<Map<String, Object>> out = store.listAll().stream()
                .filter(s -> staff || isOwner(s))
                .sorted(Comparator.comparing(InterviewSession::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(this::buildEntry)
                .collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/session/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        InterviewSession s = store.get(id);
        if (s == null || !canRead(s)) {
            return ResponseEntity.status(404).body(Map.of("error", "session not found"));
        }
        return ResponseEntity.ok(buildEntry(s));
    }

    @DeleteMapping("/session/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        InterviewSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireManage(s);
        if (guard != null) return guard;
        store.delete(id);
        return ResponseEntity.ok(Map.of("sessionId", id));
    }

    @GetMapping("/sessions/by-candidate")
    public ResponseEntity<List<Map<String, Object>>> byCandidate(
            @RequestParam String enrollmentId,
            @RequestParam(required = false, defaultValue = "10") int limit) {
        boolean staff = isStaff();
        List<Map<String, Object>> out = store.listByEnrollment(enrollmentId).stream()
                .filter(s -> staff || isOwner(s))
                .sorted(Comparator.comparing(InterviewSession::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(Math.max(1, limit))
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", s.getSessionId());
                    m.put("sessionId", s.getSessionId());
                    m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
                    m.put("endedAt", s.getEndedAt());
                    m.put("label", s.getLabel());
                    m.put("status", s.getStatus());
                    m.put("avgScore", s.getAvgScore());
                    m.put("difficulty", s.getDifficulty());
                    m.put("category", s.getCategory());
                    m.put("completed", s.getCompleted());
                    return m;
                })
                .collect(Collectors.toList());
        return ResponseEntity.ok(out);
    }

    @GetMapping("/candidates/stats")
    public ResponseEntity<List<Map<String, Object>>> allCandidateStats() {
        Map<String, List<InterviewSession>> byEnrollment = store.listAll().stream()
                .filter(s -> s.getEnrollmentId() != null && !s.getEnrollmentId().isBlank())
                .collect(Collectors.groupingBy(InterviewSession::getEnrollmentId));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, List<InterviewSession>> e : byEnrollment.entrySet()) {
            out.add(aggregate(e.getKey(), e.getValue()));
        }
        out.sort(Comparator.comparing(m -> (String) m.get("lastPracticedAt"),
                Comparator.nullsLast(Comparator.reverseOrder())));
        return ResponseEntity.ok(out);
    }

    // Candidate stats are derived from their sessions so deletes stay consistent.
    private Map<String, Object> aggregate(String enrollmentId, List<InterviewSession> sessions) {
        long practiceCount = sessions.size();
        long scoredCount = 0;
        double sumScore = 0;
        Double bestScore = null;
        String candidateName = null;
        Instant last = null;
        for (InterviewSession s : sessions) {
            if (s.getAvgScore() != null) {
                scoredCount++;
                sumScore += s.getAvgScore();
                if (bestScore == null || s.getAvgScore() > bestScore) bestScore = s.getAvgScore();
            }
            if (s.getCandidateName() != null && !s.getCandidateName().isBlank()) candidateName = s.getCandidateName();
            if (s.getCreatedAt() != null && (last == null || s.getCreatedAt().isAfter(last))) last = s.getCreatedAt();
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enrollmentId", enrollmentId);
        m.put("candidateName", candidateName);
        m.put("practiceCount", practiceCount);
        m.put("scoredCount", scoredCount);
        m.put("avgScore", scoredCount > 0 ? sumScore / scoredCount : null);
        m.put("bestScore", bestScore);
        m.put("lastPracticedAt", last != null ? last.toString() : null);
        return m;
    }

    private Map<String, Object> buildEntry(InterviewSession s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getSessionId());
        m.put("sessionId", s.getSessionId());
        m.put("status", s.getStatus());
        m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        m.put("startedAt", s.getStartedAt());
        m.put("endedAt", s.getEndedAt());
        m.put("label", s.getLabel());
        m.put("completed", s.getCompleted());
        m.put("candidateName", s.getCandidateName());
        m.put("enrollmentId", s.getEnrollmentId());
        m.put("difficulty", s.getDifficulty());
        m.put("category", s.getCategory());
        m.put("analysisMode", s.getAnalysisMode());
        m.put("questionCount", s.getQuestionCount());
        m.put("avgScore", s.getAvgScore());
        m.put("qaList", parse(s.getQaListJson()));
        m.put("transcripts", parse(s.getTranscriptsJson()));
        m.put("questionTimestamps", parse(s.getQuestionTimestampsJson()));
        m.put("llmAnalysis", parse(s.getLlmAnalysisJson()));
        m.put("createdBy", s.getCreatedBy());
        m.put("createdByEmail", s.getCreatedByEmail());
        m.put("updatedBy", s.getUpdatedBy());
        m.put("updatedByEmail", s.getUpdatedByEmail());
        m.put("isOwner", isOwner(s));
        return m;
    }

    private String serialize(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize interview field", e);
        }
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    private Double computeAvgScore(JsonNode llm) {
        if (llm == null || llm.isNull()) return null;
        JsonNode perQuestion = llm.get("perQuestion");
        if (perQuestion == null || !perQuestion.isArray() || perQuestion.isEmpty()) return null;
        double sum = 0;
        int n = 0;
        for (JsonNode q : perQuestion) {
            JsonNode score = q.get("score");
            if (score != null && score.isNumber()) {
                sum += score.asDouble();
                n++;
            }
        }
        return n > 0 ? sum / n : null;
    }

    private String deriveStatus(InterviewSession s) {
        boolean finished = Boolean.TRUE.equals(s.getCompleted()) || (s.getEndedAt() != null && !s.getEndedAt().isBlank());
        boolean hasLlm = s.getLlmAnalysisJson() != null && !s.getLlmAnalysisJson().isBlank();
        if (hasLlm || hasRealTranscript(s.getTranscriptsJson())) return "ANALYZED";
        if (finished) return "ENDED";
        return "ACTIVE";
    }

    private boolean hasRealTranscript(String transcriptsJson) {
        JsonNode arr = parse(transcriptsJson);
        if (arr == null || !arr.isArray()) return false;
        for (JsonNode t : arr) {
            if (t == null || t.isNull()) continue;
            if (t.isTextual()) {
                String v = t.asText();
                if (!v.isBlank() && !"[Transcription error]".equals(v)) return true;
            } else if (t.isObject()) {
                if (t.path("pending").asBoolean(false)) continue;
                if (!t.path("text").asText("").isBlank()) return true;
                if (t.path("words").isArray() && !t.path("words").isEmpty()) return true;
            }
        }
        return false;
    }

    private String newSessionId() {
        return UUID.randomUUID().toString();
    }

    private String caller() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.isAuthenticated()) ? a.getName() : null;
    }

    // dev fallback: unauthenticated → treated as staff + owner
    private boolean unauthenticated() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated();
    }

    private boolean isStaff() {
        if (unauthenticated()) return true;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF_AUTHORITIES::contains);
    }

    private boolean isOwner(InterviewSession s) {
        if (unauthenticated()) return true;
        String caller = caller();
        return caller != null && caller.equals(s.getCreatedBy());
    }

    private boolean canRead(InterviewSession s) { return isStaff() || isOwner(s); }
    private boolean canManage(InterviewSession s) { return isStaff() || isOwner(s); }

    private ResponseEntity<Map<String, Object>> requireManage(InterviewSession s) {
        if (s == null || !canRead(s)) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "session not found"));
        if (!canManage(s)) return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        return null;
    }

    public record SaveRequest(
            String sessionId,
            String label,
            String startedAt,
            String endedAt,
            Boolean completed,
            String candidateName,
            String enrollmentId,
            String createdByEmail,
            String updatedByEmail,
            String difficulty,
            String category,
            String analysisMode,
            JsonNode qaList,
            JsonNode transcripts,
            JsonNode questionTimestamps,
            JsonNode llmAnalysis
    ) {}
}
