package com.example.transcript.config;

import com.example.transcript.directstt.DirectSttTranscriptResultConsumerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DirectSttTranscriptResultConsumerProperties.class)
public class DirectSttTranscriptResultConsumerConfig {
}
