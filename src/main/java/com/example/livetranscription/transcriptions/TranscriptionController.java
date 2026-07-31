package com.example.livetranscription.transcriptions;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.config.RoleGroups;
import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/transcriptions")
public class TranscriptionController {

    private static final Set<String> STAFF_AUTHORITIES =
            Arrays.stream(RoleGroups.STAFF).map(r -> "ROLE_" + r).collect(Collectors.toSet());

    private static final String SCOPE_MINE = "mine";
    private static final String SCOPE_ALL = "all";

    private final TranscriptionSessionStore store;
    private final ObjectMapper objectMapper;

    public TranscriptionController(TranscriptionSessionStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    // ── Create ──────────────────────────────────────────────────────────────

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> create(@RequestBody CreateRequest req) {
        if (req.label() != null && req.label().length() > BackendDefaults.MAX_SESSION_LABEL_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "session name exceeds maximum length of " + BackendDefaults.MAX_SESSION_LABEL_CHARS + " characters"));
        }
        if (req.notes() != null && req.notes().length() > BackendDefaults.MAX_SESSION_NOTES_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "notes exceed maximum length of " + BackendDefaults.MAX_SESSION_NOTES_CHARS + " characters"));
        }

        String sessionId = (req.sessionId() != null && !req.sessionId().isBlank())
                ? req.sessionId() : UUID.randomUUID().toString();
        String caller = caller();

        TranscriptionSession s = new TranscriptionSession();
        s.setSessionId(sessionId);
        s.setStatus("ACTIVE");
        s.setCreatedAt(Instant.now());
        s.setStartedAt(req.startedAt());
        s.setLabel(req.label());
        s.setCategory(req.category());
        s.setNotes(req.notes());
        s.setCreatedByEmail(req.createdByEmail());
        s.setUpdatedByEmail(req.createdByEmail());
        s.setCreatedBy(caller);
        s.setUpdatedBy(caller);
        s.setCandidateName(req.candidateName());
        s.setEnrollmentId(req.enrollmentId());
        s.setTask(req.task());
        s.setInterviewDateTime(req.interviewDateTime());
        s.setClient(req.client());
        s.setCallTaker(req.callTaker());
        s.setVendor(req.vendor());
        s.setDuration(req.duration());
        s.setOutcome(req.outcome());
        applyLines(s, req.lines());
        store.save(s);

        return ResponseEntity.ok(Map.of("ok", true, "sessionId", sessionId));
    }

    // ── Recording writes (creator only) ─────────────────────────────────────

    @PostMapping("/session/{id}/checkpoint")
    public ResponseEntity<Map<String, Object>> checkpoint(@PathVariable String id, @RequestBody LinesRequest req) {
        TranscriptionSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireOwner(s);
        if (guard != null) return guard;
        applyLines(s, req.lines());
        if (req.durationMs() != null) s.setDurationMs(req.durationMs());
        s.setStatus("ACTIVE");
        s.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) s.setUpdatedByEmail(req.updatedByEmail());
        store.save(s);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", id));
    }

    @PostMapping("/session/{id}/end")
    public ResponseEntity<Map<String, Object>> end(@PathVariable String id, @RequestBody LinesRequest req) {
        TranscriptionSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireOwner(s);
        if (guard != null) return guard;
        if (req.lines() != null) applyLines(s, req.lines());
        if (req.durationMs() != null) s.setDurationMs(req.durationMs());
        if (s.getLineCount() == null || s.getLineCount() == 0) {
            store.delete(id);
            return ResponseEntity.ok(Map.of("ok", true, "sessionId", id, "deleted", true));
        }
        s.setStatus("ENDED");
        s.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) s.setUpdatedByEmail(req.updatedByEmail());
        store.save(s);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", id, "status", "ENDED"));
    }

    @PostMapping("/session/{id}/continue")
    public ResponseEntity<Map<String, Object>> continueSession(@PathVariable String id) {
        TranscriptionSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireOwner(s);
        if (guard != null) return guard;
        s.setStatus("ACTIVE");
        s.setUpdatedBy(caller());
        s.setUpdatedByEmail(s.getCreatedByEmail());
        store.save(s);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", id);
        out.put("label", s.getLabel());
        out.put("lines", parseLines(s.getLinesJson()));
        return ResponseEntity.ok(out);
    }

    // ── Manage (staff or owner) ─────────────────────────────────────────────

    @PostMapping("/session/{id}/rename")
    public ResponseEntity<Map<String, Object>> rename(@PathVariable String id, @RequestBody RenameRequest req) {
        if (req.label() == null || req.label().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "label is required"));
        }
        if (req.label().length() > BackendDefaults.MAX_SESSION_LABEL_CHARS) {
            return ResponseEntity.badRequest().body(Map.of("ok", false,
                    "error", "session name exceeds maximum length of " + BackendDefaults.MAX_SESSION_LABEL_CHARS + " characters"));
        }
        TranscriptionSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireManage(s);
        if (guard != null) return guard;
        s.setLabel(req.label().trim());
        s.setUpdatedBy(caller());
        if (req.updatedByEmail() != null) s.setUpdatedByEmail(req.updatedByEmail());
        store.save(s);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", id, "label", s.getLabel()));
    }

    @DeleteMapping("/session/{id}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable String id) {
        TranscriptionSession s = store.get(id);
        ResponseEntity<Map<String, Object>> guard = requireManage(s);
        if (guard != null) return guard;
        store.delete(id);
        return ResponseEntity.ok(Map.of("ok", true, "sessionId", id));
    }

    // ── Read ────────────────────────────────────────────────────────────────

    private static String effectiveStatus(TranscriptionSession s) {
        if ("ACTIVE".equals(s.getStatus()) && s.getUpdatedAt() != null
                && s.getUpdatedAt().isBefore(Instant.now().minusSeconds(BackendDefaults.SESSION_ACTIVE_WINDOW_SECONDS))) {
            return "ENDED";
        }
        return s.getStatus();
    }

    @GetMapping("/sessions")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(name = "scope", required = false, defaultValue = SCOPE_MINE) String scope,
            @RequestParam(name = "page", required = false, defaultValue = "1") int page,
            @RequestParam(name = "size", required = false, defaultValue = "0") int size,
            @RequestParam(name = "text", required = false) String text,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "client", required = false) String client,
            @RequestParam(name = "task", required = false) String task,
            @RequestParam(name = "callTaker", required = false) String callTaker,
            @RequestParam(name = "date", required = false) String date) {

        boolean canViewAll = isStaff();
        boolean viewingAll = canViewAll && SCOPE_ALL.equalsIgnoreCase(scope);

        List<TranscriptionSession> scoped = store.listSummaries().stream()
                .filter(s -> viewingAll || isOwner(s))
                .collect(Collectors.toList());

        List<TranscriptionSession> matched = scoped.stream()
                .filter(s -> matches(s, text, status, category, client, task, callTaker, date))
                .sorted(Comparator.comparing(TranscriptionSession::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .collect(Collectors.toList());

        int pageSize = size > 0
                ? Math.min(size, BackendDefaults.SESSION_PAGE_SIZE_MAX)
                : BackendDefaults.SESSION_PAGE_SIZE_DEFAULT;
        int pageNumber = Math.max(page, 1);
        int from = Math.min((pageNumber - 1) * pageSize, matched.size());
        int to = Math.min(from + pageSize, matched.size());
        List<TranscriptionSession> pageItems = matched.subList(from, to);

        Map<String, String> lines = store.linesJsonFor(pageItems.stream()
                .map(TranscriptionSession::getSessionId).collect(Collectors.toList()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (TranscriptionSession s : pageItems) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sessionId", s.getSessionId());
            m.put("label", s.getLabel());
            m.put("status", effectiveStatus(s));
            m.put("category", s.getCategory());
            m.put("candidateName", s.getCandidateName());
            m.put("enrollmentId", s.getEnrollmentId());
            m.put("task", s.getTask());
            m.put("interviewDateTime", s.getInterviewDateTime());
            m.put("client", s.getClient());
            m.put("callTaker", s.getCallTaker());
            m.put("vendor", s.getVendor());
            m.put("duration", s.getDuration());
            m.put("outcome", s.getOutcome());
            m.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
            m.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
            m.put("lineCount", s.getLineCount());
            m.put("createdBy", s.getCreatedBy());
            m.put("createdByEmail", s.getCreatedByEmail());
            m.put("updatedBy", s.getUpdatedBy());
            m.put("updatedByEmail", s.getUpdatedByEmail());
            m.put("isOwner", isOwner(s));
            m.put("preview", previewOf(lines.get(s.getSessionId())));
            items.add(m);
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", items);
        out.put("total", matched.size());
        out.put("page", pageNumber);
        out.put("size", pageSize);
        out.put("scope", viewingAll ? SCOPE_ALL : SCOPE_MINE);
        out.put("canViewAll", canViewAll);
        out.put("filterOptions", filterOptions(scoped));
        return ResponseEntity.ok(out);
    }

    private Map<String, Object> filterOptions(List<TranscriptionSession> scoped) {
        Map<String, Object> options = new LinkedHashMap<>();
        options.put("clients", distinctSorted(scoped, TranscriptionSession::getClient));
        options.put("tasks", distinctSorted(scoped, TranscriptionSession::getTask));
        options.put("callTakers", distinctSorted(scoped, TranscriptionSession::getCallTaker));
        return options;
    }

    private static List<String> distinctSorted(List<TranscriptionSession> sessions,
                                               java.util.function.Function<TranscriptionSession, String> field) {
        return sessions.stream()
                .map(field)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }

    private boolean matches(TranscriptionSession s, String text, String status, String category,
                            String client, String task, String callTaker, String date) {
        if (isSet(status) && !status.equals(effectiveStatus(s))) return false;
        if (isSet(category) && !category.equals(s.getCategory())) return false;
        if (isSet(client) && !client.equals(s.getClient())) return false;
        if (isSet(task) && !task.equals(s.getTask())) return false;
        if (isSet(callTaker) && !callTaker.equals(s.getCallTaker())) return false;
        if (isSet(date)) {
            String when = s.getInterviewDateTime();
            if (when == null || when.length() < 10 || !when.startsWith(date)) return false;
        }
        if (isSet(text)) {
            String needle = text.trim().toLowerCase();
            String haystack = Stream.of(s.getLabel(), s.getCandidateName(), s.getClient(),
                            s.getCreatedByEmail(), s.getCreatedBy())
                    .filter(v -> v != null && !v.isBlank())
                    .collect(Collectors.joining(" "))
                    .toLowerCase();
            if (!haystack.contains(needle)) return false;
        }
        return true;
    }

    private static boolean isSet(String v) {
        return v != null && !v.isBlank();
    }

    @GetMapping("/session/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        TranscriptionSession s = store.get(id);
        if (s == null || !canRead(s)) {
            return ResponseEntity.status(404).body(Map.of("ok", false, "error", "session not found"));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("sessionId", s.getSessionId());
        out.put("label", s.getLabel());
        out.put("status", s.getStatus());
        out.put("category", s.getCategory());
        out.put("notes", s.getNotes());
        out.put("startedAt", s.getStartedAt());
        out.put("durationMs", s.getDurationMs());
        out.put("candidateName", s.getCandidateName());
        out.put("enrollmentId", s.getEnrollmentId());
        out.put("task", s.getTask());
        out.put("interviewDateTime", s.getInterviewDateTime());
        out.put("client", s.getClient());
        out.put("callTaker", s.getCallTaker());
        out.put("vendor", s.getVendor());
        out.put("duration", s.getDuration());
        out.put("outcome", s.getOutcome());
        out.put("createdAt", s.getCreatedAt() != null ? s.getCreatedAt().toString() : null);
        out.put("updatedAt", s.getUpdatedAt() != null ? s.getUpdatedAt().toString() : null);
        out.put("createdBy", s.getCreatedBy());
        out.put("createdByEmail", s.getCreatedByEmail());
        out.put("updatedBy", s.getUpdatedBy());
        out.put("updatedByEmail", s.getUpdatedByEmail());
        out.put("isOwner", isOwner(s));
        out.put("lines", parseLines(s.getLinesJson()));
        return ResponseEntity.ok(out);
    }

    // ── Auth helpers ────────────────────────────────────────────────────────

    private String caller() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return (a != null && a.isAuthenticated()) ? a.getName() : null;
    }

    /** Dev fallback: no Cognito configured / unauthenticated → treat as staff + owner. */
    private boolean unauthenticated() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a == null || a instanceof AnonymousAuthenticationToken || !a.isAuthenticated();
    }

    private boolean isStaff() {
        if (unauthenticated()) return true;
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        return a.getAuthorities().stream().map(GrantedAuthority::getAuthority).anyMatch(STAFF_AUTHORITIES::contains);
    }

    private boolean isOwner(TranscriptionSession s) {
        if (unauthenticated()) return true;
        String caller = caller();
        return caller != null && caller.equals(s.getCreatedBy());
    }

    private boolean canRead(TranscriptionSession s) { return isStaff() || isOwner(s); }
    private boolean canManage(TranscriptionSession s) { return isStaff() || isOwner(s); }

    /** 404 if missing/unreadable; 403 if readable but not the creator. */
    private ResponseEntity<Map<String, Object>> requireOwner(TranscriptionSession s) {
        if (s == null || !canRead(s)) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "session not found"));
        if (!isOwner(s)) return ResponseEntity.status(403).body(Map.of("ok", false, "error", "only the creator can record on this session"));
        return null;
    }

    /** 404 if missing/unreadable; 403 if readable but not manageable. */
    private ResponseEntity<Map<String, Object>> requireManage(TranscriptionSession s) {
        if (s == null || !canRead(s)) return ResponseEntity.status(404).body(Map.of("ok", false, "error", "session not found"));
        if (!canManage(s)) return ResponseEntity.status(403).body(Map.of("ok", false, "error", "not permitted"));
        return null;
    }

    // ── Line helpers ────────────────────────────────────────────────────────

    private void applyLines(TranscriptionSession s, List<Line> lines) {
        List<Line> safe = lines != null ? lines : List.of();
        try {
            s.setLinesJson(objectMapper.writeValueAsString(safe));
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize transcript lines", e);
        }
        s.setLineCount(safe.size());
    }

    private List<Line> parseLines(String linesJson) {
        if (linesJson == null || linesJson.isBlank()) return List.of();
        try {
            return objectMapper.readValue(linesJson, new TypeReference<List<Line>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String previewOf(String linesJson) {
        StringBuilder sb = new StringBuilder();
        for (Line l : parseLines(linesJson)) {
            if (l.text() == null || l.text().isBlank()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(l.text().trim());
            if (sb.length() > 200) break;
        }
        return sb.length() > 200 ? sb.substring(0, 200) + "…" : sb.toString();
    }

    // ── DTOs ────────────────────────────────────────────────────────────────

    public record Line(String time, String text, Long ts) {}

    public record CreateRequest(
            String sessionId,
            String label,
            String category,
            String notes,
            String createdByEmail,
            Long startedAt,
            String candidateName,
            String enrollmentId,
            String task,
            String interviewDateTime,
            String client,
            String callTaker,
            String vendor,
            String duration,
            String outcome,
            List<Line> lines
    ) {}

    public record LinesRequest(List<Line> lines, Long durationMs, String updatedByEmail) {}

    public record RenameRequest(String label, String updatedByEmail) {}
}
