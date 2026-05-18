package com.example.livetranscription.service.openai;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.config.OpenAiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class OpenAiTtsService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiTtsService.class);

    public static final List<Map<String, String>> VOICES = List.of(
            Map.of("id", "alloy",   "name", "Alloy",   "gender", "Neutral"),
            Map.of("id", "echo",    "name", "Echo",    "gender", "Male"),
            Map.of("id", "fable",   "name", "Fable",   "gender", "Male"),
            Map.of("id", "onyx",    "name", "Onyx",    "gender", "Male"),
            Map.of("id", "nova",    "name", "Nova",    "gender", "Female"),
            Map.of("id", "shimmer", "name", "Shimmer", "gender", "Female")
    );

    private static final Set<String> VALID_VOICE_IDS = Set.of(
            "alloy", "echo", "fable", "onyx", "nova", "shimmer"
    );
    private static final String DEFAULT_VOICE = "alloy";

    private final WebClient openAiWebClient;
    private final OpenAiProperties props;

    public OpenAiTtsService(WebClient openAiWebClient, OpenAiProperties props) {
        this.openAiWebClient = openAiWebClient;
        this.props = props;
    }

    public List<Map<String, String>> listVoices() {
        return VOICES;
    }

    public byte[] synthesize(String text, String voice, String format) {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenAI API key not configured");
        }
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Text is required");
        }

        String resolvedVoice = VALID_VOICE_IDS.contains(voice) ? voice : DEFAULT_VOICE;
        String resolvedFormat = (format == null || format.isBlank()) ? BackendDefaults.OPENAI_TTS_FORMAT : format;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", BackendDefaults.OPENAI_TTS_MODEL);
        body.put("input", text);
        body.put("voice", resolvedVoice);
        body.put("response_format", resolvedFormat);

        byte[] audio;
        try {
            audio = openAiWebClient.post()
                    .uri("/audio/speech")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(byte[].class)
                    .block();
        } catch (WebClientResponseException e) {
            log.warn("openai_tts_upstream_error voice={} format={} status={} body={}",
                    resolvedVoice, resolvedFormat, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI TTS call failed: " + e.getStatusCode());
        }

        if (audio == null || audio.length == 0) {
            log.warn("openai_tts_empty_response voice={} format={}", resolvedVoice, resolvedFormat);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Empty TTS response from OpenAI");
        }
        return audio;
    }
}
