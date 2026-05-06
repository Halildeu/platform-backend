package com.serban.notify.provider;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.Environment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ProductionConfigValidator unit test (Faz 23.2 PR-A — Codex 019dfae5).
 */
class ProductionConfigValidatorTest {

    @Test
    void nonProdProfileSkipsValidation() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "test" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "dev-only-pepper-not-for-production",  // intentionally bad
            "dev-only-secret-not-for-production",
            "dev-only-key-not-for-production",
            false, false, false, false  // all production guards off
        );

        // No throw — non-prod skips
        v.validate();
    }

    @Test
    void prodProfileWithDefaultPepperFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "dev-only-pepper-not-for-production",  // ← problem
            "rotated-webhook-secret",
            "rotated-authz-key",
            true, true, true, true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.redaction.pepper=DEFAULT");
    }

    @Test
    void prodProfileWithDkimDisabledFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "rotated-webhook-secret",
            "rotated-authz-key",
            false,  // ← DKIM off
            true, true, true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.dkim.enabled=false");
    }

    @Test
    void prodProfileWithSmtpTlsDisabledFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "production" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "rotated-webhook-secret",
            "rotated-authz-key",
            true,
            false,  // ← TLS enforce off
            true, true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.smtp.tls.enforce=false");
    }

    @Test
    void prodProfileWithDefaultAuthzKeyFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "rotated-webhook-secret",
            "dev-only-key-not-for-production",  // ← default authz key
            true, true, true, true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.authz.internal-api-key=DEFAULT");
    }

    @Test
    void prodProfileWithAuthzDisabledFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "rotated-webhook-secret",
            "rotated-authz-key",
            true, true, true,
            false  // ← authz off
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.authz.enabled=false");
    }

    @Test
    void prodProfileWithPreferencesDisabledFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "rotated-webhook-secret",
            "rotated-authz-key",
            true, true,
            false,  // ← preferences off
            true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.preferences.enabled=false");
    }

    @Test
    void prodProfileWithDefaultWebhookSecretFails() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper",
            "dev-only-secret-not-for-production",  // ← default webhook secret
            "rotated-authz-key",
            true, true, true, true
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("notify.adapters.webhook.signing-secret=DEFAULT");
    }

    @Test
    void prodProfileWithAllRotatedPasses() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "rotated-pepper-from-vault",
            "rotated-webhook-secret",
            "rotated-authz-key",
            true, true, true, true
        );

        // No throw — all guards passed
        v.validate();
    }

    @Test
    void prodProfileMultipleErrorsAggregated() {
        Environment env = mock(Environment.class);
        when(env.getActiveProfiles()).thenReturn(new String[] { "prod" });

        ProductionConfigValidator v = new ProductionConfigValidator(
            env,
            "dev-only-pepper-not-for-production",
            "dev-only-secret-not-for-production",
            "dev-only-key-not-for-production",
            false, false, false, false
        );

        assertThatThrownBy(v::validate)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("(7 error(s))");
    }
}
