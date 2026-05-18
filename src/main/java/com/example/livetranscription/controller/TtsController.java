package com.example.livetranscription.controller;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.service.openai.OpenAiTtsService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tts")
public class TtsController {

    private final OpenAiTtsService ttsService;

    public TtsController(OpenAiTtsService ttsService) {
        this.ttsService = ttsService;
    }

    @GetMapping("/voices")
    public List<Map<String, String>> voices() {
        return ttsService.listVoices();
    }

    public record SpeechRequest(
            @NotBlank @Size(max = BackendDefaults.MAX_TTS_TEXT_CHARS) String text,
            String voice,
            String format
    ) {}

    @PostMapping("/speech")
    public ResponseEntity<byte[]> speech(@Valid @RequestBody SpeechRequest req) {
        String fmt = (req.format() == null || req.format().isBlank()) ? "mp3" : req.format();
        byte[] audio = ttsService.synthesize(req.text(), req.voice(), fmt);

        MediaType contentType = switch (fmt.toLowerCase()) {
            case "wav"  -> MediaType.parseMediaType("audio/wav");
            case "opus" -> MediaType.parseMediaType("audio/opus");
            case "aac"  -> MediaType.parseMediaType("audio/aac");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            default     -> MediaType.parseMediaType("audio/mpeg");
        };

        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(audio);
    }
}
