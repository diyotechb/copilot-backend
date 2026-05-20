@Autowired
    private com.example.service.InterviewSummaryService interviewSummaryService;
package com.example.controller;

import com.example.model.InterviewSession;
import com.example.model.InterviewSummary;
import com.example.service.InterviewService;
import com.example.service.ResumeService;
import com.example.dynamodb.DynamoPersistenceService;
import com.example.openai.OpenAIConversationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/interview")
public class InterviewController {
    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
    @Autowired
    private InterviewService interviewService;

    @Autowired
    private ResumeService resumeService;

    @Autowired
    private DynamoPersistenceService dynamoPersistenceService;

    @Autowired
    private OpenAIConversationService openAIConversationService;

    @PostMapping("/start")
    public ResponseEntity<InterviewSession> startInterview(@RequestParam String candidateId) {
        InterviewSession session = interviewService.startInterview(candidateId);
        log.info("[Interview] Created new session: {} for candidate {}", session.getSessionId(), candidateId);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<String> uploadResumeText(@PathVariable String sessionId, @RequestBody ResumeTextRequest req) {
        // 1. Summarize resume
        String summary = resumeService.summarizeResume(req.getResumeText());
        // 2. Store summary in DynamoDB
        InterviewSession session = dynamoPersistenceService.getSession(sessionId);
        session.setResumeSummary(summary);
        dynamoPersistenceService.saveSession(session);
        // 3. Inject resume summary into OpenAI conversation (once)
        openAIConversationService.injectResumeSummary(session.getConversationId(), summary);
        log.info("[Interview] Resume summary injected for session: {}", sessionId);
        return ResponseEntity.ok("Resume text processed and summary injected.");
    }

    public static class ResumeTextRequest {
        private String resumeText;
        public String getResumeText() { return resumeText; }
        public void setResumeText(String resumeText) { this.resumeText = resumeText; }
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<InterviewSummary> completeInterview(@PathVariable String sessionId) {
        interviewService.completeInterview(sessionId);
        // Should return summary (placeholder)
        // 1. Fetch all messages for session
        var messages = dynamoPersistenceService.getMessages(sessionId);
        // 2. Build conversation as list of strings
        var conversation = messages.stream().map(m -> m.getRole() + ": " + m.getContent()).toList();
        // 3. Generate summary using OpenAI
        InterviewSummary summary = interviewSummaryService.generateSummary(sessionId, conversation);
        // 4. Save and return summary
        dynamoPersistenceService.saveInterviewSummary(sessionId, summary.toString());
        return ResponseEntity.ok(summary);
    }

    // Optionally, add a GET endpoint to fetch session or messages if needed
}
