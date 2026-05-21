package com.example.livetranscription.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.DynamoDbClientBuilder;

import java.net.URI;

@Configuration
public class DynamoDBConfig {

    @Value("${aws.accessKey:}")
    private String accessKey;

    @Value("${aws.secretKey:}")
    private String secretKey;

    @Value("${aws.dynamodb.region:us-east-1}")
    private String region;

    @Value("${aws.dynamodb.endpoint:}")
    private String endpoint;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        DynamoDbClientBuilder builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(
                                accessKey != null ? accessKey : "",
                                secretKey != null ? secretKey : "")));
        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }
}
