package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.util.List;

@Service
public class SampleContentService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789";

    private final OpenAiChatService chat;

    public SampleContentService(OpenAiChatService chat) {
        this.chat = chat;
    }

    public String generateResume(String keywords) {
        return generate(buildResumePrompt(keywords));
    }

    public String generateJobDescription(String keywords) {
        return generate(buildJobDescriptionPrompt(keywords));
    }

    private String generate(String prompt) {
        String text = chat.chat(new OpenAiChatService.ChatRequest(
                BackendDefaults.OPENAI_CHAT_MODEL,
                List.of(new OpenAiChatService.Message("user", prompt)),
                1.1,
                false
        ));
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty response from OpenAI");
        }
        return text.trim();
    }

    private String buildResumePrompt(String keywords) {
        String seed = randomSeed();
        String kw = keywords == null || keywords.isBlank()
                ? "Pick a random tech role and seniority level."
                : "Candidate profile keywords: " + keywords + ".";

        return """
                Generate a realistic 1-page resume in plain text.
                %s

                Variation hint: %s

                Requirements:
                - 400-600 words, plain text only (no markdown, no asterisks, no code fences)
                - Sections in this order: NAME (line 1), CONTACT (one line, placeholder values), SUMMARY, EXPERIENCE (2-3 jobs, each with 3-5 bullet points and realistic dates), EDUCATION, SKILLS, PROJECTS (1-2 items)
                - If the keywords contain a person's name, use it as the candidate's name. Otherwise invent a realistic fictional name.
                - If the keywords contain a company name, use it as the candidate's most recent or current employer. Other employers, dates, and locations should be realistic but fictional.
                - Make the content specific and varied; avoid generic interview-prep phrasing

                Output only the resume content. No preamble, no explanation, no closing remarks.
                """.formatted(kw, seed);
    }

    private String buildJobDescriptionPrompt(String keywords) {
        String seed = randomSeed();
        String kw = keywords == null || keywords.isBlank()
                ? "Pick a random tech role and seniority level."
                : "Use these keywords: " + keywords + ".";

        return """
                Generate a realistic job description in plain text.
                %s

                Variation hint: %s

                Requirements:
                - 300-450 words, plain text only (no markdown, no asterisks, no code fences)
                - Sections in this order: ROLE TITLE & SENIORITY, COMPANY (1-2 line overview), RESPONSIBILITIES (5-7 bullets), REQUIRED QUALIFICATIONS (5-7 bullets), PREFERRED QUALIFICATIONS (3-4 bullets), LOCATION (remote / hybrid / onsite), SALARY RANGE
                - If the keywords contain a company name, use it as the hiring company. Otherwise invent a realistic fictional company name.
                - Make the content specific and varied

                Output only the job description content. No preamble, no explanation.
                """.formatted(kw, seed);
    }

    private static String randomSeed() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return sb.toString();
    }
}
