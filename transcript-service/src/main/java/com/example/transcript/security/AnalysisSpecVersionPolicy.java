package com.example.transcript.security;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Server-owned exact allowlist for analysis job specifications. */
@Component
public class AnalysisSpecVersionPolicy {

    private final Set<String> allowedVersions;

    public AnalysisSpecVersionPolicy(
            @Value("${security.analysis-job-capability.allowed-analysis-spec-versions:meeting-intelligence-v1}")
            String configuredVersions) {
        if (!StringUtils.hasText(configuredVersions)) {
            throw new IllegalArgumentException("analysis spec version allowlist must not be blank");
        }
        LinkedHashSet<String> parsed = new LinkedHashSet<>();
        Arrays.stream(configuredVersions.split(",", -1)).forEach(value -> {
            String candidate = value.trim();
            if (!StringUtils.hasText(candidate) || candidate.length() > 64) {
                throw new IllegalArgumentException("analysis spec version allowlist contains an invalid value");
            }
            if (!parsed.add(candidate)) {
                throw new IllegalArgumentException("analysis spec version allowlist contains a duplicate value");
            }
        });
        this.allowedVersions = Set.copyOf(parsed);
    }

    public void requireAllowed(String requestedVersion) {
        if (!StringUtils.hasText(requestedVersion) || requestedVersion.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANALYSIS_SPEC_VERSION_INVALID");
        }
        if (!allowedVersions.contains(requestedVersion)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ANALYSIS_SPEC_VERSION_NOT_ALLOWED");
        }
    }
}
