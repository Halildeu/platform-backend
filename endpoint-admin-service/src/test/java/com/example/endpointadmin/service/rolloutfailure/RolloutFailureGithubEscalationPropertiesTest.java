package com.example.endpointadmin.service.rolloutfailure;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RolloutFailureGithubEscalationPropertiesTest {

    @Test
    void disabledAllowsBlankOperatorConfig() {
        RolloutFailureGithubEscalationProperties p =
                new RolloutFailureGithubEscalationProperties(false, "", "", "", "",
                        "", null, null, 0);

        assertThat(p.enabled()).isFalse();
        assertThat(p.apiBaseUrl()).isEqualTo("https://api.github.com");
        assertThat(p.connectTimeout()).isEqualTo(Duration.ofSeconds(5));
        assertThat(p.readTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(p.toString()).contains("token=<unset>").doesNotContain("ghp_");
    }

    @Test
    void enabledRequiresHttpsRepoAndToken() {
        assertThatThrownBy(() -> new RolloutFailureGithubEscalationProperties(
                true, "http://api.github.test", "Halildeu", "platform-backend", "secret-token",
                "ua", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("https");

        assertThatThrownBy(() -> new RolloutFailureGithubEscalationProperties(
                true, "https://api.github.test", "Halildeu", "platform-backend", "",
                "ua", Duration.ofSeconds(1), Duration.ofSeconds(1), 1024))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("token");
    }

    @Test
    void toStringRedactsConfiguredToken() {
        RolloutFailureGithubEscalationProperties p =
                new RolloutFailureGithubEscalationProperties(true, "https://api.github.test",
                        "Halildeu", "platform-backend", "ghp_secret_123", "ua",
                        Duration.ofSeconds(1), Duration.ofSeconds(1), 1024);

        assertThat(p.toString())
                .contains("token=<redacted>")
                .doesNotContain("ghp_secret_123");
    }
}
