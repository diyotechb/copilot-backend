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
        OpenAiTtsService.TtsResult result = ttsService.synthesize(req.text(), req.voice(), fmt);

        MediaType contentType = switch (fmt.toLowerCase()) {
            case "wav"  -> MediaType.parseMediaType("audio/wav");
            case "opus" -> MediaType.parseMediaType("audio/opus");
            case "aac"  -> MediaType.parseMediaType("audio/aac");
            case "flac" -> MediaType.parseMediaType("audio/flac");
            default     -> MediaType.parseMediaType("audio/mpeg");
        };

        // Response is fully determined by (model|voice|format|text), so the
        // hash doubles as a stable ETag and the body is safe to cache for a
        // year. immutable lets any future CDN serve repeats without revalidation.
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=31536000, immutable")
                .header(HttpHeaders.ETAG, result.etag())
                .body(result.audio());
    }
}
