package com.example.livetranscription.voice;

import com.example.livetranscription.service.openai.OpenAiChatService.Message;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Conversation context for the live interview assistant.
 * Starts with a generic interview-assistant prompt.
 * Once buildSession() is called with a candidate's resume + past winning Q&As,
 * the effective system prompt is enriched and stays that way for the lifetime
 * of this context (up to 1 hour per InterviewContextStore TTL).
 */
public class InterviewConversationContext extends ConversationContext {

    private static final int MAX_HISTORY   = 10;
    private static final int MAX_PAST_QAS  = 8;

    private static final String BASE_PROMPT = """
            You are a real-time interview coaching assistant. A software engineer candidate is in a live job interview and you are reading the live transcript of what the interviewer just said.

            YOUR JOB: when the interviewer asks a question, write a concise, natural-sounding answer that the candidate can read and say out loud immediately.

            ANSWER RULES
            - Write in first person as the candidate speaking ("I built…", "In my last role…")
            - Keep answers to 3–5 sentences — complete enough to be useful, short enough to say in one breath
            - Sound conversational and genuine, not scripted or corporate
            - Include at least one concrete detail, example, or metric when relevant
            - Behavioral questions: naturally cover situation → action → result in one paragraph, no labels
            - Technical questions: give a direct practical answer with a brief real-world grounding
            - If the resume below is provided, ground every answer in the candidate's actual experience — do not invent projects or tools not mentioned

            WHEN NOT TO ANSWER
            - If the transcript is small talk, a greeting, an acknowledgment, or clearly not a question, reply with exactly: —
            - If the transcript is too short or ambiguous to be a real question, reply with exactly: —

            FORBIDDEN WORDS — never use: ensure, leverage, utilize, implement, robust, seamless, streamline, facilitate, spearhead, orchestrate, holistic, paradigm, deliverables
            Plain replacements: "make sure" not "ensure" · "use" not "leverage/utilize" · "build/set up" not "implement" · "help" not "facilitate"

            FORBIDDEN OPENERS — never start a response with: "Great question", "I would say", "As a candidate", "Certainly", "Of course", "Absolutely"

            OUTPUT FORMAT
            Reply with only the answer text or the single character —
            No labels, no markdown, no explanation, no meta-commentary.
            """;

    private final Deque<Message> history = new ArrayDeque<>();
    private volatile String effectivePrompt = BASE_PROMPT;
    private volatile boolean sessionBuilt = false;

    public InterviewConversationContext(String conversationId) {
        super(conversationId);
    }

    /**
     * Enriches the system prompt with the candidate's resume and past successful Q&As.
     * Called once from the session-builder REST endpoint before the interview starts.
     */
    public synchronized void buildSession(String resumeText, List<QaPair> pastQAs) {
        StringBuilder sb = new StringBuilder(BASE_PROMPT);

        if (resumeText != null && !resumeText.isBlank()) {
            sb.append("\n\n--- CANDIDATE RESUME ---\n")
              .append(resumeText.trim())
              .append("\n--- END RESUME ---\n")
              .append("\nWhen answering questions, draw specifically on the projects, technologies, companies, ")
              .append("and achievements listed in the resume above. Do not invent details not present there.\n");
        }

        if (pastQAs != null && !pastQAs.isEmpty()) {
            int limit = Math.min(pastQAs.size(), MAX_PAST_QAS);
            sb.append("\n\n--- PAST SUCCESSFUL INTERVIEW Q&As (STYLE REFERENCE) ---\n")
              .append("The following are examples of interview questions and strong answers from past sessions. ")
              .append("Match this tone, depth, and specificity in your responses.\n\n");
            for (int i = 0; i < limit; i++) {
                QaPair qa = pastQAs.get(i);
                if (qa.question() != null && qa.answer() != null) {
                    sb.append("Q: ").append(qa.question().trim()).append("\n")
                      .append("A: ").append(qa.answer().trim()).append("\n\n");
                }
            }
            sb.append("--- END PAST Q&As ---\n");
        }

        effectivePrompt = sb.toString();
        sessionBuilt = true;
        history.clear(); // fresh history for this session
    }

    public boolean isSessionBuilt() {
        return sessionBuilt;
    }

    @Override
    public synchronized List<Message> buildMessages(String userText) {
        List<Message> out = new ArrayList<>(history.size() + 2);
        out.add(new Message("system", effectivePrompt));
        out.addAll(history);
        out.add(new Message("user", userText));
        return out;
    }

    @Override
    public synchronized void recordExchange(String userText, String assistantText) {
        history.addLast(new Message("user", userText));
        history.addLast(new Message("assistant", assistantText));
        while (history.size() > MAX_HISTORY) history.removeFirst();
    }

    public record QaPair(String question, String answer) {}
}
