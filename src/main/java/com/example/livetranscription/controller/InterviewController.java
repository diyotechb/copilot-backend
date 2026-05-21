package com.example.livetranscription.controller;

import com.example.livetranscription.dynamodb.DynamoPersistenceService;
import com.example.livetranscription.model.InterviewSession;
import com.example.livetranscription.model.InterviewSummary;
import com.example.livetranscription.service.InterviewService;
import com.example.livetranscription.service.InterviewSummaryService;
import com.example.livetranscription.service.OpenAIConversationService;
import com.example.livetranscription.service.ResumeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/proxy/interview")
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

    @Autowired
    private InterviewSummaryService interviewSummaryService;

    @PostMapping("/start")
    public ResponseEntity<InterviewSession> startInterview(@RequestParam String candidateId) {
        InterviewSession session = interviewService.startInterview(candidateId);
        log.info("[Interview] Created new session: {} for candidate {}", session.getSessionId(), candidateId);
        return ResponseEntity.ok(session);
    }

    @PostMapping("/{sessionId}/resume")
    public ResponseEntity<String> uploadResumeText(@PathVariable String sessionId, @RequestBody ResumeTextRequest req) {
        String summary = resumeService.summarizeResume(req.getResumeText());
        InterviewSession session = dynamoPersistenceService.getSession(sessionId);
        session.setResumeSummary(summary);
        dynamoPersistenceService.saveSession(session);
        openAIConversationService.injectResumeSummary(session.getConversationId(), summary);
        log.info("[Interview] Resume summary injected for session: {}", sessionId);
        return ResponseEntity.ok("Resume text processed and summary injected.");
    }

    @PostMapping("/{sessionId}/complete")
    public ResponseEntity<InterviewSummary> completeInterview(@PathVariable String sessionId) {
        interviewService.completeInterview(sessionId);
        var messages = dynamoPersistenceService.getMessages(sessionId);
        var conversation = messages.stream().map(m -> m.getRole() + ": " + m.getContent()).toList();
        InterviewSummary summary = interviewSummaryService.generateSummary(sessionId, conversation);
        dynamoPersistenceService.saveInterviewSummary(sessionId, summary.toString());
        return ResponseEntity.ok(summary);
    }

    public static class ResumeTextRequest {
        private String resumeText;

        public String getResumeText() { return resumeText; }
        public void setResumeText(String resumeText) { this.resumeText = resumeText; }
    }
}
