package com.example.livetranscription.voice;

import com.example.livetranscription.config.BackendDefaults;
import com.example.livetranscription.config.OpenAiProperties;
import com.example.livetranscription.service.openai.OpenAiChatService.Message;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VoiceOpenAiStreamService {

    private static final String DONE = "[DONE]";

    private final WebClient openAiWebClient;
    private final OpenAiProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public VoiceOpenAiStreamService(WebClient openAiWebClient, OpenAiProperties props) {
        this.openAiWebClient = openAiWebClient;
        this.props = props;
    }

    public Flux<String> stream(List<Message> messages) {
        if (!props.isConfigured()) {
            return Flux.error(new IllegalStateException("OpenAI API key not configured"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", BackendDefaults.OPENAI_CHAT_MODEL);
        body.put("stream", true);
        body.put("messages", messages);

        return openAiWebClient.post()
                .uri("/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(String.class)
                .takeWhile(line -> !DONE.equals(line.trim()))
                .map(this::extractDelta)
                .filter(s -> !s.isEmpty());
    }

    private String extractDelta(String json) {
        try {
            JsonNode node = mapper.readTree(json);
            JsonNode delta = node.path("choices").path(0).path("delta").path("content");
            return delta.isMissingNode() || delta.isNull() ? "" : delta.asText();
        } catch (Exception e) {
            return "";
        }
    }
}
