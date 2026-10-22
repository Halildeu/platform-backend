package com.example.transcript.config;

import com.example.transcript.attribution.SessionAttributionConsumerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Registers the default-off #3746 session attribution consumer properties. */
@Configuration
@EnableConfigurationProperties(SessionAttributionConsumerProperties.class)
public class SessionAttributionConsumerConfig {
}
