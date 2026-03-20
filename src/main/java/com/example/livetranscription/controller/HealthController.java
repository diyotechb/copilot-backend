package com.example.livetranscription.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Controller to handle AWS Elastic Beanstalk health check requests.
 * By default, many EB configurations poll "/health-check".
 */
@RestController
public class HealthController {

    @GetMapping("/health-check")
    public String healthCheck() {
        return "OK";
    }
}
