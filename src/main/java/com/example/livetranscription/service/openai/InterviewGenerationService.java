package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

@Service
public class InterviewGenerationService {

    private static final Logger log = LoggerFactory.getLogger(InterviewGenerationService.class);

    private static final int BATCH_SIZE = 10;
    private static final int PARALLEL_BATCHES = 3;
    private static final int MAX_ROUNDS = 6;
    private static final double DEDUP_THRESHOLD = 0.5;

    private static final Map<String, int[]> DIFFICULTY_BUDGET = Map.of(
            "Beginner",     new int[]{20, 25},
            "Intermediate", new int[]{25, 30},
            "Advanced",     new int[]{30, 35}
    );
    private static final String DIFFICULTY_DEFAULT = "Beginner";

    private static final List<String> INTRO_KEYWORDS = List.of(
            "tell me about yourself", "introduce yourself", "your background", "introduction",
            "kick things off", "your journey", "how you started", "how you became", "your story",
            "your path", "developer journey", "engineering journey", "kick off"
    );

    private static final List<Pattern> CLOSURE_PHRASES = List.of(
            Pattern.compile("\\blastly\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfinally\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\b(to\\s+)?wrap\\s*(up|things\\s*up)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blast(\\s+but\\s+not\\s+least)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\blast\\s+question\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bto\\s+close\\s+(out|things)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("\\bfor\\s+my\\s+last\\s+question\\b", Pattern.CASE_INSENSITIVE)
    );

    private static final Pattern LEADING_GREETING = Pattern.compile(
            "^(hi|hello|welcome|nice to meet you|thanks for joining|good (morning|afternoon|evening))\\s*([\\w']+\\s*)?[,!.]?\\s*",
            Pattern.CASE_INSENSITIVE
    );

    private static final Set<String> DEDUP_STOPWORDS = Set.of(
            "the","a","an","is","are","was","were","be","been","being","have","has","had",
            "do","does","did","will","would","can","could","should","may","might","must",
            "and","or","but","so","if","as","at","by","for","in","of","on","to","with","from",
            "into","about","after","again","before","between","down","just","more","most",
            "now","off","once","only","other","out","own","same","such","than","then","there",
            "through","too","very","when","where","which","who","whom","why","how","what",
            "while","still","also","because","since","i","you","we","they","it","me","us",
            "my","your","his","her","its","our","their","some","any","each","every","no",
            "this","that","these","those","here","well","really","tell","share","describe",
            "walk","explain","give","let","think","sounds","interesting","great",
            "ok","okay","um","uh","like"
    );

    private final OpenAiChatService chat;
    private final ObjectMapper mapper;

    public InterviewGenerationService(OpenAiChatService chat, ObjectMapper mapper) {
        this.chat = chat;
        this.mapper = mapper;
    }

    public void generate(GenerateRequest req, GenerationListener listener) {
        if (req == null || req.resumeText == null || req.resumeText.isBlank()) {
            listener.onError(new ResponseStatusException(HttpStatus.BAD_REQUEST, "resumeText is required"));
            return;
        }

        String level = DIFFICULTY_BUDGET.containsKey(req.difficulty) ? req.difficulty : DIFFICULTY_DEFAULT;
        int[] budget = DIFFICULTY_BUDGET.get(level);
        int minCount = budget[0];
        int maxCount = budget[1];

        Set<String> seen = new HashSet<>();
        List<QA> pool = new ArrayList<>();
        boolean[] firstBatchDone = {false};
        int batchNum = 1;

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_BATCHES);
        try {
            int rounds = 0;
            while (mainQuestionCount(pool) < minCount && rounds < MAX_ROUNDS) {
                List<CompletableFuture<List<QA>>> futures = new ArrayList<>();
                for (int i = 0; i < PARALLEL_BATCHES; i++) {
                    final int thisBatch = batchNum++;
                    futures.add(CompletableFuture.supplyAsync(() -> fetchBatch(req, level, thisBatch), executor));
                }
                CompletableFuture<Void> all = CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
                all.join();

                for (CompletableFuture<List<QA>> f : futures) {
                    List<QA> items = f.getNow(List.of());
                    addAndDedupe(items, seen, pool);
                }

                if (!firstBatchDone[0]) firstBatchDone[0] = true;

                int ready = mainQuestionCount(pool);
                List<QA> snapshot = assembleFinalQA(pool, maxCount);
                listener.onProgress(ready, minCount, firstBatchDone[0]);
                listener.onUpdate(snapshot);

                rounds++;
            }

            listener.onDone(assembleFinalQA(pool, maxCount));
        } catch (Throwable t) {
            listener.onError(t);
        } finally {
            executor.shutdown();
        }
    }

    private List<QA> fetchBatch(GenerateRequest req, String level, int batchNum) {
        boolean isFirstBatch = (batchNum == 1);
        List<String> batchTopics = isFirstBatch ? List.of() : InterviewPromptBuilder.pickTopicsForBatch(2);

        String prompt = InterviewPromptBuilder.buildPrompt(
                req.resumeText,
                req.jobDescriptionText,
                BATCH_SIZE,
                level,
                req.category,
                req.preferredKeywords,
                isFirstBatch,
                batchTopics
        );
        return chatAndParse(prompt, "batch " + batchNum);
    }

    /** One generic batch for the shared daily bank (resume-free). */
    private List<QA> fetchGenericBatch(String level, String category, boolean isFirstBatch, int batchNum) {
        List<String> batchTopics = isFirstBatch ? List.of() : InterviewPromptBuilder.pickTopicsForBatch(2);
        String prompt = InterviewPromptBuilder.buildGenericPrompt(BATCH_SIZE, level, category, isFirstBatch, batchTopics);
        return chatAndParse(prompt, "bank batch " + batchNum);
    }

    private String chatRaw(String prompt) {
        return chat.chat(new OpenAiChatService.ChatRequest(
                BackendDefaults.OPENAI_CHAT_MODEL,
                List.of(
                        new OpenAiChatService.Message("system", "You are an interview assistant that outputs only JSON."),
                        new OpenAiChatService.Message("user", prompt)
                ),
                0.9,
                false
        ));
    }

    private List<QA> chatAndParse(String prompt, String label) {
        return parseBatch(chatRaw(prompt), label);
    }

    private List<QA> parseBatch(String raw, String label) {
        String cleaned = raw.replaceAll("(?i)```(json)?\\s*", "").replaceAll("```$", "").trim();
        try {
            JsonNode node = mapper.readTree(cleaned);
            if (!node.isArray()) {
                log.warn("interview_batch_non_array_response label={} preview={}", label, cleaned.substring(0, Math.min(200, cleaned.length())));
                return List.of();
            }
            List<QA> out = new ArrayList<>(node.size());
            for (JsonNode item : node) {
                String q = item.path("question").asText("").trim();
                String a = item.path("answer").asText("");
                String type = item.path("type").asText("");
                if (!q.isEmpty()) out.add(new QA(q, a, type));
            }
            return out;
        } catch (Exception e) {
            log.warn("interview_batch_parse_failed label={} error={}", label, e.getMessage());
            return List.of();
        }
    }

    /** Difficulty min/max main-question budget, defaulting when the level is unknown. */
    public static int[] budgetFor(String level) {
        String l = DIFFICULTY_BUDGET.containsKey(level) ? level : DIFFICULTY_DEFAULT;
        return DIFFICULTY_BUDGET.get(l);
    }

    /**
     * Build a shared, resume-free question pool for one category+difficulty. Returns
     * the typed pool (openers + format + intro + main questions) and the number of
     * OpenAI calls spent, for cost accounting. Mirrors {@link #generate}'s batch loop.
     *
     * @param category the bank category label; {@code "MIXED"} (or null) means no category override
     */
    public BankPool generateBankPool(String category, String difficulty) {
        String level = DIFFICULTY_BUDGET.containsKey(difficulty) ? difficulty : DIFFICULTY_DEFAULT;
        String cat = (category == null || "MIXED".equalsIgnoreCase(category)) ? null : category;
        int target = BackendDefaults.QUESTION_BANK_TARGET_MAIN;

        Set<String> seen = new HashSet<>();
        List<QA> pool = new ArrayList<>();
        int calls = 0;
        int batchNum = 1;

        ExecutorService executor = Executors.newFixedThreadPool(PARALLEL_BATCHES);
        try {
            int rounds = 0;
            while (mainQuestionCount(pool) < target && rounds < BackendDefaults.QUESTION_BANK_MAX_ROUNDS) {
                List<CompletableFuture<List<QA>>> futures = new ArrayList<>();
                for (int i = 0; i < PARALLEL_BATCHES; i++) {
                    final int thisBatch = batchNum++;
                    final boolean isFirstBatch = (thisBatch == 1);
                    calls++;
                    futures.add(CompletableFuture.supplyAsync(
                            () -> fetchGenericBatch(level, cat, isFirstBatch, thisBatch), executor));
                }
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                for (CompletableFuture<List<QA>> f : futures) {
                    addAndDedupe(f.getNow(List.of()), seen, pool);
                }
                rounds++;
            }
        } finally {
            executor.shutdown();
        }
        return new BankPool(pool, calls);
    }

    /**
     * Generate the per-candidate personalized top-up: a single batch split between
     * resume-anchored and keyword-anchored questions, all tagged {@code main}.
     */
    public List<QA> generateTopUp(GenerateRequest req) {
        String level = DIFFICULTY_BUDGET.containsKey(req.difficulty) ? req.difficulty : DIFFICULTY_DEFAULT;
        String prompt = InterviewPromptBuilder.buildTopUpPrompt(
                req.resumeText,
                req.jobDescriptionText,
                level,
                req.category,
                req.preferredKeywords,
                BackendDefaults.TOPUP_RESUME_QUESTIONS,
                BackendDefaults.TOPUP_KEYWORD_QUESTIONS
        );
        List<QA> raw = chatAndParse(prompt, "topup");
        List<QA> out = new ArrayList<>(raw.size());
        Set<String> seen = new HashSet<>();
        for (QA item : raw) {
            if (item.question() != null && !item.question().isBlank() && seen.add(item.question())) {
                out.add(new QA(item.question(), item.answer(), "main"));
            }
        }
        return out;
    }

    /** Result of building a shared bank: the questions plus OpenAI calls spent. */
    public record BankPool(List<QA> questions, int openAiCalls) {}

    private static synchronized void addAndDedupe(List<QA> items, Set<String> seen, List<QA> pool) {
        for (QA item : items) {
            if (!seen.contains(item.question())) {
                seen.add(item.question());
                pool.add(item);
            }
        }
    }

    private static int mainQuestionCount(List<QA> pool) {
        int n = 0;
        for (QA item : pool) {
            if ("opener".equals(item.type()) || "format".equals(item.type())) continue;
            String q = item.question().toLowerCase();
            boolean isIntro = INTRO_KEYWORDS.stream().anyMatch(q::contains);
            if (isIntro) continue;
            n++;
        }
        return n;
    }

    static List<QA> assembleFinalQA(List<QA> pool, int maxCount) {
        List<QA> rawOpeners = pool.stream().filter(i -> "opener".equals(i.type())).toList();
        List<QA> formatStatements = pool.stream().filter(i -> "format".equals(i.type())).toList();
        List<QA> mainQuestions = pool.stream().filter(i -> i.type() == null || i.type().isEmpty() || "main".equals(i.type())).toList();

        List<QA> intrusiveIntroOpeners = rawOpeners.stream().filter(InterviewGenerationService::looksLikeIntro).toList();
        List<QA> openers = rawOpeners.stream().filter(q -> !looksLikeIntro(q)).toList();

        List<QA> introCandidatesFromMain = mainQuestions.stream().filter(InterviewGenerationService::looksLikeIntro).toList();
        List<QA> allIntros = new ArrayList<>(introCandidatesFromMain);
        allIntros.addAll(intrusiveIntroOpeners);
        List<QA> singleIntro = allIntros.isEmpty() ? List.of() : List.of(allIntros.get(0));

        List<QA> otherMainQuestions = new ArrayList<>(mainQuestions.stream().filter(q -> !looksLikeIntro(q)).toList());
        otherMainQuestions = dedupSemantically(otherMainQuestions, DEDUP_THRESHOLD);

        List<QA> cappedMain = otherMainQuestions.subList(0, Math.min(maxCount, otherMainQuestions.size()));

        List<QA> combined = new ArrayList<>();
        combined.addAll(openers);
        if (!formatStatements.isEmpty()) combined.add(formatStatements.get(0));
        combined.addAll(singleIntro);
        combined.addAll(cappedMain);

        if (openers.isEmpty() && !combined.isEmpty()) {
            combined.add(0, new QA(
                    "Hi, thanks so much for hopping on with me today. Before we get into things, how's your day going so far?",
                    "Hey, doing well, thanks. Glad to be here and excited for our chat.",
                    "opener"
            ));
        }

        List<QA> finalList = new ArrayList<>(combined.size());
        int lastIndex = combined.size() - 1;
        for (int i = 0; i < combined.size(); i++) {
            QA item = combined.get(i);
            String q = item.question() == null ? "" : item.question();
            if (i != 0) q = LEADING_GREETING.matcher(q).replaceAll("");
            if (i != lastIndex) {
                for (Pattern re : CLOSURE_PHRASES) {
                    q = re.matcher(q).replaceAll(match -> {
                        String m = match.group();
                        if (m.toLowerCase().startsWith("lastly") || m.toLowerCase().startsWith("finally")) return "Now";
                        return "";
                    });
                }
                q = q.replaceAll("^[\\s,]+", "").replaceAll("\\s{2,}", " ").trim();
            }
            if (!q.isEmpty()) q = Character.toUpperCase(q.charAt(0)) + q.substring(1);
            finalList.add(new QA(q, item.answer(), null));
        }
        return finalList;
    }

    private static boolean looksLikeIntro(QA item) {
        if (item.question() == null) return false;
        String q = item.question().toLowerCase();
        return INTRO_KEYWORDS.stream().anyMatch(q::contains);
    }

    private static List<QA> dedupSemantically(List<QA> items, double threshold) {
        List<QA> kept = new ArrayList<>();
        List<Set<String>> sigs = new ArrayList<>();
        for (QA item : items) {
            Set<String> sig = signature(item.question());
            boolean isDup = false;
            for (Set<String> k : sigs) {
                if (jaccard(sig, k) >= threshold) { isDup = true; break; }
            }
            if (!isDup) {
                kept.add(item);
                sigs.add(sig);
            }
        }
        return kept;
    }

    private static Set<String> signature(String text) {
        Set<String> out = new HashSet<>();
        if (text == null) return out;
        String[] tokens = text.toLowerCase().replaceAll("[^a-z0-9 ]+", " ").split("\\s+");
        for (String tok : tokens) {
            if (tok.length() < 3 || DEDUP_STOPWORDS.contains(tok)) continue;
            out.add(stem(tok));
        }
        return out;
    }

    private static String stem(String w) {
        int n = w.length();
        if (n > 5 && (w.endsWith("ings") || w.endsWith("ying"))) return w.substring(0, n - 3);
        if (n > 4 && w.endsWith("ing")) return w.substring(0, n - 3);
        if (n > 4 && w.endsWith("ed")) return w.substring(0, n - 2);
        if (n > 3 && w.endsWith("s")) return w.substring(0, n - 1);
        return w;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0.0;
        int intersect = 0;
        for (String x : a) if (b.contains(x)) intersect++;
        return (double) intersect / (a.size() + b.size() - intersect);
    }

    public static List<Map<String, String>> toClientShape(List<QA> qa) {
        List<Map<String, String>> out = new ArrayList<>(qa.size());
        for (QA item : qa) {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("question", item.question());
            m.put("answer", item.answer() == null ? "" : item.answer());
            out.add(m);
        }
        return out;
    }

    public record QA(String question, String answer, String type) {}

    public record GenerateRequest(
            @NotBlank @Size(max = BackendDefaults.MAX_RESUME_CHARS) String resumeText,
            @Size(max = BackendDefaults.MAX_JOB_DESCRIPTION_CHARS) String jobDescriptionText,
            String difficulty,
            String category,
            @Size(max = BackendDefaults.MAX_PREFERRED_KEYWORDS)
            List<@Size(max = BackendDefaults.MAX_PREFERRED_KEYWORD_CHARS) String> preferredKeywords,
            // When true (honored for staff only), bypass the daily cache and generate
            // a fully resume-driven interview — today's behavior. Null/false = cached.
            Boolean fullyPersonalized
    ) {}

    public interface GenerationListener {
        void onProgress(int ready, int target, boolean firstBatchDone);
        void onUpdate(List<QA> assembled);
        void onDone(List<QA> assembled);
        void onError(Throwable t);
    }
}
