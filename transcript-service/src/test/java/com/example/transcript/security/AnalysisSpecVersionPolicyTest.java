package com.example.transcript.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalysisSpecVersionPolicyTest {

    @Test
    void exactConfiguredVersion_isAllowed() {
        AnalysisSpecVersionPolicy policy = new AnalysisSpecVersionPolicy(
                "meeting-intelligence-v1,meeting-intelligence-v2");

        policy.requireAllowed("meeting-intelligence-v1");
        policy.requireAllowed("meeting-intelligence-v2");
    }

    @Test
    void callerValue_isNotTrimmedOrCallerSelected() {
        AnalysisSpecVersionPolicy policy = new AnalysisSpecVersionPolicy("meeting-intelligence-v1");

        assertThatThrownBy(() -> policy.requireAllowed(" meeting-intelligence-v1 "))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(403));
        assertThatThrownBy(() -> policy.requireAllowed("meeting-intelligence-v2"))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        ex -> assertThat(ex.getStatusCode().value()).isEqualTo(403));
    }

    @Test
    void invalidConfiguration_failsStartup() {
        assertThatThrownBy(() -> new AnalysisSpecVersionPolicy(" "))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisSpecVersionPolicy("meeting-intelligence-v1,"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisSpecVersionPolicy(
                "meeting-intelligence-v1,meeting-intelligence-v1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
