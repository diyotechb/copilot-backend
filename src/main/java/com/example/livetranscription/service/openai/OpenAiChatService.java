package com.example.livetranscription.service.openai;

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

@Service
public class OpenAiChatService {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatService.class);

    private final WebClient openAiWebClient;
    private final OpenAiProperties props;

    public OpenAiChatService(WebClient openAiWebClient, OpenAiProperties props) {
        this.openAiWebClient = openAiWebClient;
        this.props = props;
    }

    public String chat(ChatRequest req) {
        if (!props.isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "OpenAI API key not configured");
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", req.model());
        body.put("messages", req.messages());
        if (req.temperature() != null) body.put("temperature", req.temperature());
        if (req.jsonObject()) body.put("response_format", Map.of("type", "json_object"));

        Map<String, Object> response;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> r = openAiWebClient.post()
                    .uri("/chat/completions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            response = r;
        } catch (WebClientResponseException e) {
            log.warn("openai_chat_upstream_error model={} status={} body={}", req.model(), e.getStatusCode(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "OpenAI chat call failed: " + e.getStatusCode());
        }

        if (response == null) {
            log.warn("openai_chat_null_response model={}", req.model());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Null response from OpenAI");
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
        if (choices == null || choices.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No choices in OpenAI response");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
        if (message == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Missing message in OpenAI choice");
        }

        Object content = message.get("content");
        return content == null ? "" : content.toString();
    }

    public record Message(String role, String content) {}

    public record ChatRequest(
            String model,
            List<Message> messages,
            Double temperature,
            boolean jsonObject
    ) {}
}
