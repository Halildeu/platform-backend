package com.example.report.contexthealth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "context-health")
public record ContextHealthProperties(
        String dataDir,
        String indexDir,
        int cacheTtlSeconds,
        boolean enabled
) {
    public ContextHealthProperties {
        if (cacheTtlSeconds <= 0) cacheTtlSeconds = 30;
    }
}
