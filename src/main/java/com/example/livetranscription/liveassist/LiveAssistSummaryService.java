package com.example.livetranscription.liveassist;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.LiveAssistMessage;
import com.example.livetranscription.service.openai.OpenAiChatService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class LiveAssistSummaryService {

    private static final Logger log = LoggerFactory.getLogger(LiveAssistSummaryService.class);

    private static final String SYSTEM_PROMPT = """
            You are an interview evaluator. You are given the transcript of a job interview as a list of
            interviewer questions and the candidate's answers. Assess the candidate's performance.

            Return a JSON object with exactly these keys:
            {
              "strengths":  [ up to 4 short bullet strings ],
              "weaknesses": [ up to 4 short bullet strings ],
              "score":      integer 1-10,
              "overall":    one or two sentence summary string
            }

            Base every point only on what the transcript shows. If the transcript is too short to judge,
            say so in "overall", use an empty strengths/weaknesses list, and give a low score.
            Return only the JSON object, no markdown, no extra text.
            """;

    private final DynamoPersistenceService persistence;
    private final OpenAiChatService chat;
    private final ObjectMapper mapper = new ObjectMapper();

    public LiveAssistSummaryService(DynamoPersistenceService persistence, OpenAiChatService chat) {
        this.persistence = persistence;
        this.chat = chat;
    }

    public String generateSummary(String sessionId) {
        List<LiveAssistMessage> messages = persistence.getMessages(sessionId);
        if (messages == null || messages.isEmpty()) {
            return "{\"strengths\":[],\"weaknesses\":[],\"score\":0,\"overall\":\"No conversation was recorded for this session.\"}";
        }

        String transcript = buildTranscript(messages);
        try {
            String json = chat.chat(new OpenAiChatService.ChatRequest(
                    BackendDefaults.VOICE_CHAT_MODEL,
                    List.of(
                            new OpenAiChatService.Message("system", SYSTEM_PROMPT),
                            new OpenAiChatService.Message("user", transcript)
                    ),
                    0.3,
                    true
            ));
            mapper.readTree(json);
            return json;
        } catch (Exception e) {
            log.warn("LiveAssist summary generation failed for session {}", sessionId, e);
            return "{\"strengths\":[],\"weaknesses\":[],\"score\":0,\"overall\":\"Summary could not be generated.\"}";
        }
    }

    private String buildTranscript(List<LiveAssistMessage> messages) {
        StringBuilder sb = new StringBuilder();
        for (LiveAssistMessage m : messages) {
            String label = "assistant".equals(m.getRole()) ? "Candidate answer" : "Interviewer";
            sb.append(label).append(": ").append(m.getContent() == null ? "" : m.getContent().trim()).append("\n\n");
        }
        return sb.toString();
    }
}
