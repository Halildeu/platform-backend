package com.example.gpcore.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * dev-bypass must be profile-guarded, not merely documented (Codex 019f1913
 * post-impl #3): enabling it outside a dev profile fails the application startup.
 */
class DevBypassGuardTest {

    // --- pure rule ---

    @Test
    void disabledBypass_alwaysOk() {
        assertDoesNotThrow(() -> DevBypassGuard.validate(false, List.of()));
        assertDoesNotThrow(() -> DevBypassGuard.validate(false, List.of("prod")));
    }

    @Test
    void enabledBypass_underDevProfile_ok() {
        assertDoesNotThrow(() -> DevBypassGuard.validate(true, List.of("dev")));
        assertDoesNotThrow(() -> DevBypassGuard.validate(true, List.of("local")));
        assertDoesNotThrow(() -> DevBypassGuard.validate(true, List.of("test")));
        assertDoesNotThrow(() -> DevBypassGuard.validate(true, List.of("prod", "dev")));
    }

    @Test
    void enabledBypass_withoutDevProfile_throws() {
        assertThrows(IllegalStateException.class, () -> DevBypassGuard.validate(true, List.of()));
        assertThrows(IllegalStateException.class, () -> DevBypassGuard.validate(true, List.of("prod")));
        assertThrows(IllegalStateException.class, () -> DevBypassGuard.validate(true, List.of("k8s")));
    }

    // --- wiring: startup fail-fast ---

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner().withUserConfiguration(DevBypassGuardConfig.class);

    @Test
    void context_failsFast_whenBypassEnabledUnderNonDevProfile() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
                .withPropertyValues("gp.authz.dev-bypass=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void context_ok_whenBypassEnabledUnderDevProfile() {
        runner.withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("dev"))
                .withPropertyValues("gp.authz.dev-bypass=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void context_ok_whenBypassDisabled() {
        runner.withPropertyValues("gp.authz.dev-bypass=false")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }
}
