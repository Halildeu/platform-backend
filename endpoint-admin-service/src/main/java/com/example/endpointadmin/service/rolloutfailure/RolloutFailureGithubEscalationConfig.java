package com.example.endpointadmin.service.rolloutfailure;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.http.HttpClient;

@Configuration
@EnableConfigurationProperties(RolloutFailureGithubEscalationProperties.class)
public class RolloutFailureGithubEscalationConfig {

    @Bean
    RolloutFailureIssuePublisher rolloutFailureIssuePublisher(
            RolloutFailureGithubEscalationProperties properties) {
        HttpClient http = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(properties.connectTimeout())
                .build();
        return new GitHubRolloutFailureIssuePublisher(properties, http);
    }
}
