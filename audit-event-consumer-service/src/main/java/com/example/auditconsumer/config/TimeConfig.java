package com.example.auditconsumer.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — {@link Clock} bean so the
 * persistence service's ingest-time logic is test-injectable (endpoint-admin
 * {@code TimeConfig} reuse).
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
