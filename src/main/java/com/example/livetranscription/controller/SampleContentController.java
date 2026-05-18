package com.example.livetranscription.controller;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.service.openai.SampleContentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/sample")
public class SampleContentController {

    private final SampleContentService service;

    public SampleContentController(SampleContentService service) {
        this.service = service;
    }

    public record SampleRequest(@Size(max = BackendDefaults.MAX_SAMPLE_KEYWORDS_CHARS) String keywords) {}

    @PostMapping("/resume")
    public Map<String, String> resume(@Valid @RequestBody(required = false) SampleRequest req) {
        String keywords = req == null ? null : req.keywords();
        return Map.of("text", service.generateResume(keywords));
    }

    @PostMapping("/job-description")
    public Map<String, String> jobDescription(@Valid @RequestBody(required = false) SampleRequest req) {
        String keywords = req == null ? null : req.keywords();
        return Map.of("text", service.generateJobDescription(keywords));
    }
}
