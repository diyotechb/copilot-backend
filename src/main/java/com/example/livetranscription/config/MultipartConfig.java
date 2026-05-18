package com.example.livetranscription.config;

import jakarta.servlet.MultipartConfigElement;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MultipartConfig {

    @Bean
    public MultipartConfigElement multipartConfigElement() {
        return new MultipartConfigElement(
                "",
                BackendDefaults.MULTIPART_MAX_FILE_SIZE_BYTES,
                BackendDefaults.MULTIPART_MAX_REQUEST_SIZE_BYTES,
                0
        );
    }
}
