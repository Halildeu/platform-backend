package com.example.transcript.config;

import com.example.transcript.finalization.TranscriptFinalizationProperties;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(TranscriptFinalizationProperties.class)
public class TranscriptFinalizationConfig {

    @Bean
    Clock transcriptFinalizationClock() {
        return Clock.systemUTC();
    }
}
