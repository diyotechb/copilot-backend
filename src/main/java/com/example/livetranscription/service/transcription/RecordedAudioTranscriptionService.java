package com.example.livetranscription.service.transcription;

import com.example.livetranscription.config.BackendDefaults;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class RecordedAudioTranscriptionService {

    private static final Logger log = LoggerFactory.getLogger(RecordedAudioTranscriptionService.class);

    private final WebClient assemblyAiWebClient;
    private final String apiKey;

    public RecordedAudioTranscriptionService(
            @Qualifier("assemblyAiWebClient") WebClient assemblyAiWebClient,
            @Value("${assemblyai.api-key}") String apiKey) {
        this.assemblyAiWebClient = assemblyAiWebClient;
        this.apiKey = apiKey;
    }

    public Map<String, Object> transcribe(byte[] audioBytes) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "AssemblyAI API key not configured");
        }
        if (audioBytes == null || audioBytes.length == 0) {
            return emptyTranscript();
        }

        String uploadUrl = upload(audioBytes);
        String transcriptId = startTranscript(uploadUrl);
        return poll(transcriptId);
    }

    private String upload(byte[] audioBytes) {
        Map<String, Object> response;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = assemblyAiWebClient.post()
                    .uri("/upload")
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .bodyValue(audioBytes)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            response = r;
        } catch (WebClientResponseException e) {
            log.warn("assemblyai_upload_error bytes={} status={} body={}",
                    audioBytes.length, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AssemblyAI upload failed: " + e.getStatusCode());
        }

        if (response == null || !(response.get("upload_url") instanceof String url) || url.isBlank()) {
            log.warn("assemblyai_upload_missing_url bytes={}", audioBytes.length);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AssemblyAI upload returned no upload_url");
        }
        return url;
    }

    private String startTranscript(String audioUrl) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("audio_url", audioUrl);
        body.put("speech_models", List.of("universal-2"));
        // sentiment_analysis was previously enabled but nothing in the
        // frontend consumed the sentiment_analysis_results field (grep
        // confirms it is only ever initialized as an empty array).
        // Disabling avoids the AssemblyAI sentiment surcharge once free-tier
        // hours are exhausted, with zero user-visible impact.
        body.put("sentiment_analysis", false);
        body.put("language_code", "en");

        Map<String, Object> response;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = assemblyAiWebClient.post()
                    .uri("/transcript")
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            response = r;
        } catch (WebClientResponseException e) {
            String detail = "";
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> err = e.getResponseBodyAs(Map.class);
                if (err != null && err.get("error") != null) detail = " " + err.get("error");
            } catch (Exception ignored) { /* keep detail blank */ }
            log.warn("assemblyai_start_error status={} body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Failed to start transcription on AssemblyAI." + detail);
        }

        if (response == null || !(response.get("id") instanceof String id) || id.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AssemblyAI did not return a transcript id");
        }
        return id;
    }

    private Map<String, Object> poll(String transcriptId) {
        for (int i = 0; i < BackendDefaults.ASSEMBLY_AI_MAX_POLL_RETRIES; i++) {
            try {
                Thread.sleep(BackendDefaults.ASSEMBLY_AI_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Polling interrupted");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = assemblyAiWebClient.get()
                    .uri("/transcript/{id}", transcriptId)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();

            if (data == null) continue;
            Object status = data.get("status");
            if ("completed".equals(status)) {
                return data;
            }
            if ("error".equals(status)) {
                String msg = data.get("error") == null ? "" : data.get("error").toString().toLowerCase();
                // AssemblyAI returns this error for silent recordings; the Summary
                // view renders an empty transcript as "No spoken answer was recorded".
                if (msg.contains("no spoken audio") || msg.contains("no speech")) {
                    return emptyTranscript();
                }
                log.warn("assemblyai_transcript_error id={} error={}", transcriptId, data.get("error"));
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "AssemblyAI error: " + data.get("error"));
            }
        }
        log.warn("assemblyai_transcript_timeout id={} maxRetries={}", transcriptId, BackendDefaults.ASSEMBLY_AI_MAX_POLL_RETRIES);
        throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "AssemblyAI transcription timed out.");
    }

    private static Map<String, Object> emptyTranscript() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("text", "");
        out.put("words", List.of());
        out.put("sentiment_analysis_results", List.of());
        return out;
    }
}
