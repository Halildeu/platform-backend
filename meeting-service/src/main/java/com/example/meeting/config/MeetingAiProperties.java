package com.example.meeting.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;

@ConfigurationProperties(prefix = "meeting.ai")
public record MeetingAiProperties(
        boolean enabled,
        URI baseUrl,
        String analyzePath,
        Duration requestTimeout,
        int maxTranscriptChars
) {

    private static final String DEFAULT_ANALYZE_PATH = "/analyze";
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int DEFAULT_MAX_TRANSCRIPT_CHARS = 200_000;

    public MeetingAiProperties {
        if (analyzePath == null || analyzePath.isBlank()) {
            analyzePath = DEFAULT_ANALYZE_PATH;
        }
        if (!analyzePath.startsWith("/")) {
            analyzePath = "/" + analyzePath;
        }
        if (requestTimeout == null || requestTimeout.isNegative() || requestTimeout.isZero()) {
            requestTimeout = DEFAULT_REQUEST_TIMEOUT;
        }
        if (maxTranscriptChars <= 0) {
            maxTranscriptChars = DEFAULT_MAX_TRANSCRIPT_CHARS;
        }
    }

    public boolean configured() {
        return enabled && baseUrl != null;
    }
}
