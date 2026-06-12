package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AnalysisService {

    private final OpenAiChatService chat;
    private final ObjectMapper mapper;

    public AnalysisService(OpenAiChatService chat, ObjectMapper mapper) {
        this.chat = chat;
        this.mapper = mapper;
    }

    public Map<String, Object> analyze(AnalyzeRequest req) {
        if (req == null || req.qaList == null || req.qaList.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "qaList is required and must not be empty");
        }

        AnalysisTypes types = normalizeTypes(req.analysisTypes);
        List<JsonNode> transcripts = req.transcripts == null ? List.of() : req.transcripts;

        List<QA> answeredQa = new ArrayList<>();
        List<JsonNode> answeredTranscripts = new ArrayList<>();
        List<Integer> indexMap = new ArrayList<>();
        for (int i = 0; i < req.qaList.size(); i++) {
            JsonNode t = i < transcripts.size() ? transcripts.get(i) : null;
            if (isSkippedSlot(t)) continue;
            answeredQa.add(req.qaList.get(i));
            answeredTranscripts.add(t);
            indexMap.add(i);
        }

        if (answeredQa.isEmpty()) {
            List<Map<String, Object>> skipped = new ArrayList<>(req.qaList.size());
            for (int i = 0; i < req.qaList.size(); i++) skipped.add(Map.of("skipped", true));
            Map<String, Object> session = new LinkedHashMap<>();
            session.put("strongestArea", "");
            session.put("growthArea", "");
            session.put("patterns", List.of());
            session.put("verdict", "No answers were recorded for this interview.");
            return assembleResponse(skipped, session, types);
        }

        String prompt = buildPrompt(answeredQa, answeredTranscripts, req.difficulty, req.category, types);

        String raw = chat.chat(new OpenAiChatService.ChatRequest(
                BackendDefaults.OPENAI_ANALYSIS_MODEL,
                List.of(
                        new OpenAiChatService.Message("system",
                                "You are a rigorous, skeptical senior interviewer grading a mock interview. Grade strictly against the reference answer and do not inflate scores — most answers are average or below. Only the dimensions the user requested. Output strict JSON only."),
                        new OpenAiChatService.Message("user", prompt)
                ),
                0.4,
                true
        ));

        JsonNode parsed;
        try {
            parsed = mapper.readTree(raw);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not parse analysis response as JSON");
        }

        List<Map<String, Object>> normalizedAnswered = normalizePerQuestion(parsed.get("perQuestion"), answeredQa.size(), types);
        List<Map<String, Object>> fullPerQuestion = new ArrayList<>(req.qaList.size());
        for (int i = 0; i < req.qaList.size(); i++) fullPerQuestion.add(Map.of("skipped", true));
        for (int j = 0; j < indexMap.size(); j++) {
            fullPerQuestion.set(indexMap.get(j), normalizedAnswered.get(j));
        }

        Map<String, Object> session = normalizeSession(parsed.get("session"));
        return assembleResponse(fullPerQuestion, session, types);
    }

    private Map<String, Object> assembleResponse(List<Map<String, Object>> perQuestion, Map<String, Object> session, AnalysisTypes types) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("perQuestion", perQuestion);
        out.put("session", session);
        out.put("analysisTypes", Map.of(
                "delivery", types.delivery(),
                "content", types.content(),
                "improvements", types.improvements()
        ));
        return out;
    }

    private static AnalysisTypes normalizeTypes(AnalysisTypes input) {
        boolean delivery = input != null && input.delivery();
        boolean content = input != null && input.content();
        boolean improvements = input != null && input.improvements();
        if (!delivery && !content && !improvements) delivery = true;
        return new AnalysisTypes(delivery, content, improvements);
    }

    private static boolean isSkippedSlot(JsonNode t) {
        if (t == null || t.isNull()) return true;
        if (t.isTextual()) {
            String s = t.asText();
            return s.isBlank() || "[Transcription error]".equals(s);
        }
        if (t.isObject()) {
            if (t.path("skipped").asBoolean(false)) return true;
            return t.path("text").asText("").isBlank();
        }
        return true;
    }

    private static String candidateText(JsonNode t) {
        if (t == null || t.isNull()) return "[no spoken answer recorded]";
        if (t.isTextual()) {
            String s = t.asText();
            if (s.isEmpty() || "[Transcription error]".equals(s)) return "[no spoken answer recorded]";
            return s;
        }
        if (t.isObject()) {
            String s = t.path("text").asText("");
            return s.isEmpty() ? "[empty]" : s;
        }
        return "[empty]";
    }

    private String buildPrompt(List<QA> qaList, List<JsonNode> transcripts, String difficulty, String category, AnalysisTypes types) {
        StringBuilder items = new StringBuilder();
        for (int i = 0; i < qaList.size(); i++) {
            QA qa = qaList.get(i);
            String text = candidateText(transcripts.get(i));
            items.append("--- Q").append(i + 1).append(" ---\n");
            items.append("Question: ").append(safe(qa.question())).append('\n');
            items.append("Candidate's spoken answer (verbatim transcript): ").append(text).append('\n');
            if (types.content() && qa.answer() != null && !qa.answer().isEmpty()) {
                items.append("Reference answer: ").append(qa.answer()).append('\n');
            }
            if (i < qaList.size() - 1) items.append('\n');
        }

        List<String> dimensions = new ArrayList<>();
        if (types.delivery()) dimensions.add("Delivery quality (grammar, tone, fillers, pace, clarity).");
        if (types.content()) dimensions.add("Answer evaluation (correctness, completeness, structure — compared against the reference answer).");
        if (types.improvements()) dimensions.add("Improvement plan (concrete things to practice next time, ranked by impact).");

        List<String> perQuestionFields = new ArrayList<>();
        perQuestionFields.add("\"score\": <integer 1-10, weighted by selected dimensions>");
        if (types.delivery()) {
            perQuestionFields.add("""
                    "deliveryScore": <integer 1-10>,
                          "deliveryNotes": {
                            "grammar": "one short observation, or empty string if clean",
                            "tone": "one short observation about tone",
                            "fillers": "one short observation about filler use",
                            "pace": "one short observation about pace",
                            "clarity": "one short observation about structure and clarity"
                          }""");
        }
        if (types.content()) {
            perQuestionFields.add("""
                    "contentScore": <integer 1-10>,
                          "contentNotes": {
                            "correctness": "did they get the answer right? short.",
                            "completeness": "did they cover the key points? short.",
                            "structure": "was the answer organized? short."
                          },
                          "keyPointsHit": ["concept they mentioned", ...],
                          "keyPointsMissed": ["concept from the reference they did not mention", ...]""");
        }
        perQuestionFields.add("\"strengths\": [\"short bullet\", ...]");
        perQuestionFields.add("\"weaknesses\": [\"short bullet\", ...]");
        if (types.improvements()) {
            perQuestionFields.add("""
                    "improvements": ["concrete actionable bullet", ...],
                          "tryNext": "one sentence — the single most important improvement\"""");
        } else {
            perQuestionFields.add("\"tryNext\": \"one short suggestion (optional)\"");
        }

        List<String> rules = new ArrayList<>();
        rules.add("- Each \"score\" / \"deliveryScore\" / \"contentScore\" is 1-10. A weak case gets a low score with a specific reason. Do not soft-pedal.");
        rules.add("- Scoring bands, apply strictly: 1-3 = Needs work (wrong, missing, or barely any real content). 4-5 = Below average (partially correct, major gaps or vague). 6-7 = Average (mostly correct with some gaps — the default for a competent answer). 8-10 = Strong (correct, complete, well-structured, little left to improve).");
        rules.add("- Be a tough grader. Most real mock-interview answers land in the 4-7 range. Reserve 8-10 for genuinely excellent answers; when uncertain between two bands, choose the lower one. Do not give 8+ just because delivery was smooth or the candidate sounded confident.");
        rules.add("- Quote 1-3 of the candidate's actual words when relevant.");
        rules.add("- \"strengths\" / \"weaknesses\": 1-3 short bullets each, max 12 words per bullet. Empty array allowed.");
        rules.add("- If the candidate gave no spoken answer, score 1 and put \"no answer recorded\" in weaknesses.");
        if (types.delivery()) {
            rules.add("- Delivery dimension is about HOW they spoke (grammar, tone, fillers, pace, clarity). Wrong answers can still earn a high deliveryScore if delivered well.");
            rules.add("- \"deliveryNotes\" entries: max 14 words each. Empty string when nothing notable.");
        }
        if (types.content()) {
            rules.add("- Content dimension is about WHAT they said vs. the reference answer (correctness, completeness, structure). Great delivery does not earn content points if the answer was wrong.");
            rules.add("- \"keyPointsHit\" / \"keyPointsMissed\": up to 5 each, drawn from the reference answer.");
        }
        if (types.improvements()) {
            rules.add("- \"improvements\": 2-4 actionable bullets. Each starts with a verb. (\"Pause 1 second instead of saying 'um'.\", \"Name the specific framework when you mention CI.\")");
        }
        rules.add("- Skip generic words (\"good\", \"nice\", \"great\"). Be specific.");

        StringBuilder dims = new StringBuilder();
        for (String d : dimensions) dims.append("- ").append(d).append('\n');

        return "You are an interview coach reviewing a mock interview. The user has asked you to evaluate the following dimensions:\n"
                + dims
                + "\nDIFFICULTY: " + safe(difficulty)
                + "\nCATEGORY: " + (category == null || category.isBlank() ? "All" : category) + "\n\n"
                + "INTERVIEW TRANSCRIPT:\n" + items + "\n\n"
                + "Return ONLY valid JSON matching this exact shape — no markdown, no commentary:\n\n"
                + "{\n  \"perQuestion\": [\n    {\n      " + String.join(",\n      ", perQuestionFields) + "\n    }\n"
                + "    // one entry per question, in the same order as input\n  ],\n"
                + "  \"session\": {\n"
                + "    \"strongestArea\": \"one sentence with concrete evidence from a specific answer\",\n"
                + "    \"growthArea\": \"one sentence with concrete evidence from a specific answer\",\n"
                + "    \"patterns\": [\"short observed pattern across answers\", ...],\n"
                + "    \"verdict\": \"one sentence overall verdict\"\n"
                + "  }\n}\n\n"
                + "Rules:\n" + String.join("\n", rules);
    }

    private List<Map<String, Object>> normalizePerQuestion(JsonNode arr, int expectedSize, AnalysisTypes types) {
        List<Map<String, Object>> out = new ArrayList<>(expectedSize);
        int provided = (arr != null && arr.isArray()) ? arr.size() : 0;
        for (int i = 0; i < expectedSize; i++) {
            JsonNode q = (i < provided) ? arr.get(i) : null;
            out.add(normalizeOneQuestion(q, types));
        }
        return out;
    }

    private Map<String, Object> normalizeOneQuestion(JsonNode q, AnalysisTypes types) {
        Map<String, Object> safe = new LinkedHashMap<>();
        if (q == null || !q.isObject()) {
            safe.put("score", null);
            safe.put("strengths", List.of());
            safe.put("weaknesses", List.of("Analysis unavailable for this question."));
            safe.put("tryNext", "");
            return safe;
        }
        Integer score = q.get("score") != null && q.get("score").isNumber() ? q.get("score").asInt() : null;
        safe.put("score", score);
        safe.put("strengths", arrayOfStrings(q.get("strengths")));
        safe.put("weaknesses", arrayOfStrings(q.get("weaknesses")));
        safe.put("tryNext", q.path("tryNext").isTextual() ? q.path("tryNext").asText() : "");

        if (types.delivery()) {
            Integer ds = q.get("deliveryScore") != null && q.get("deliveryScore").isNumber()
                    ? q.get("deliveryScore").asInt() : score;
            safe.put("deliveryScore", ds);
            JsonNode dn = q.path("deliveryNotes");
            Map<String, Object> notes = new LinkedHashMap<>();
            notes.put("grammar", dn.path("grammar").asText(""));
            notes.put("tone", dn.path("tone").asText(""));
            notes.put("fillers", dn.path("fillers").asText(""));
            notes.put("pace", dn.path("pace").asText(""));
            notes.put("clarity", dn.path("clarity").asText(""));
            safe.put("deliveryNotes", notes);
        }
        if (types.content()) {
            Integer cs = q.get("contentScore") != null && q.get("contentScore").isNumber()
                    ? q.get("contentScore").asInt() : score;
            safe.put("contentScore", cs);
            JsonNode cn = q.path("contentNotes");
            Map<String, Object> notes = new LinkedHashMap<>();
            notes.put("correctness", cn.path("correctness").asText(""));
            notes.put("completeness", cn.path("completeness").asText(""));
            notes.put("structure", cn.path("structure").asText(""));
            safe.put("contentNotes", notes);
            safe.put("keyPointsHit", arrayOfStrings(q.get("keyPointsHit")));
            safe.put("keyPointsMissed", arrayOfStrings(q.get("keyPointsMissed")));
        }
        if (types.improvements()) {
            safe.put("improvements", arrayOfStrings(q.get("improvements")));
        }
        return safe;
    }

    private Map<String, Object> normalizeSession(JsonNode s) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (s == null || !s.isObject()) {
            out.put("strongestArea", "");
            out.put("growthArea", "");
            out.put("patterns", List.of());
            out.put("verdict", "");
            return out;
        }
        out.put("strongestArea", s.path("strongestArea").asText(""));
        out.put("growthArea", s.path("growthArea").asText(""));
        out.put("patterns", arrayOfStrings(s.get("patterns")));
        out.put("verdict", s.path("verdict").asText(""));
        return out;
    }

    private static List<String> arrayOfStrings(JsonNode node) {
        if (node == null || !node.isArray()) return Collections.emptyList();
        List<String> out = new ArrayList<>(node.size());
        for (JsonNode item : node) {
            if (item.isTextual()) out.add(item.asText());
            else if (!item.isNull()) out.add(item.toString());
        }
        return out;
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    public record QA(
            @Size(max = BackendDefaults.MAX_ANALYZE_QUESTION_CHARS) String question,
            @Size(max = BackendDefaults.MAX_ANALYZE_ANSWER_CHARS) String answer
    ) {}
    public record AnalysisTypes(boolean delivery, boolean content, boolean improvements) {}
    public record AnalyzeRequest(
            @NotEmpty @Size(max = BackendDefaults.MAX_ANALYZE_QA_ITEMS) @Valid List<QA> qaList,
            @Size(max = BackendDefaults.MAX_ANALYZE_QA_ITEMS) List<JsonNode> transcripts,
            String difficulty,
            String category,
            AnalysisTypes analysisTypes
    ) {}
}
