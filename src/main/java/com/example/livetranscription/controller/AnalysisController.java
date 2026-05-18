package com.example.livetranscription.controller;

import com.example.livetranscription.service.openai.AnalysisService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/interview")
public class AnalysisController {

    private final AnalysisService service;

    public AnalysisController(AnalysisService service) {
        this.service = service;
    }

    @PostMapping("/analyze")
    public Map<String, Object> analyze(@Valid @RequestBody AnalysisService.AnalyzeRequest req) {
        return service.analyze(req);
    }
}
