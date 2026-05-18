package com.example.livetranscription.controller;

import com.example.livetranscription.service.transcription.RecordedAudioTranscriptionService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Map;

@RestController
@RequestMapping("/api/transcribe")
public class TranscribeController {

    private final RecordedAudioTranscriptionService service;

    public TranscribeController(RecordedAudioTranscriptionService service) {
        this.service = service;
    }

    @PostMapping(value = "/audio", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> transcribe(@RequestParam("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audio file part is required");
        }
        try {
            return service.transcribe(audio.getBytes());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded audio", e);
        }
    }
}
