package com.example.ethics.config;

import java.time.Duration;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ethics")
public record EthicsProperties(
        UUID publicOrgId,
        Duration mailboxSessionTtl,
        int secretIterations,
        String staffAudience,
        Boolean secureTransportRequired) {
    public EthicsProperties {
        if (publicOrgId == null) throw new IllegalArgumentException("ethics.public-org-id is required");
        if (mailboxSessionTtl == null) mailboxSessionTtl = Duration.ofMinutes(15);
        if (secretIterations < 120_000) secretIterations = 210_000;
        if (staffAudience == null || staffAudience.isBlank()) throw new IllegalArgumentException("ethics.staff-audience is required");
        if (secureTransportRequired == null) secureTransportRequired = true;
    }
}
