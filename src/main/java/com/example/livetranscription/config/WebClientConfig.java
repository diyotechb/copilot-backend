package com.example.livetranscription.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    // WebFlux default (256 KiB) is too small for analysis prompts and AssemblyAI JSON polling.
    private static final int MAX_IN_MEMORY_BYTES = 16 * 1024 * 1024;

    @Bean
    public WebClient openAiWebClient(OpenAiProperties props) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);

        if (props.isConfigured()) {
            builder.defaultHeader("Authorization", "Bearer " + props.getApiKey());
        }
        return builder.build();
    }

    @Bean
    public WebClient openAiTtsWebClient(OpenAiProperties props) {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(BackendDefaults.OPENAI_TTS_TIMEOUT_SECONDS));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();

        WebClient.Builder builder = WebClient.builder()
                .baseUrl("https://api.openai.com/v1")
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies);

        if (props.isConfigured()) {
            builder.defaultHeader("Authorization", "Bearer " + props.getApiKey());
        }
        return builder.build();
    }

    @Bean
    public WebClient assemblyAiWebClient() {
        HttpClient httpClient = HttpClient.create()
                .responseTimeout(Duration.ofSeconds(120));

        ExchangeStrategies strategies = ExchangeStrategies.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY_BYTES))
                .build();

        return WebClient.builder()
                .baseUrl(BackendDefaults.ASSEMBLY_AI_BASE_URL)
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .exchangeStrategies(strategies)
                .build();
    }
}
