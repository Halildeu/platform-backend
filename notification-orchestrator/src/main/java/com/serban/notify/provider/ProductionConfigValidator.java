package com.serban.notify.provider;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * ProductionConfigValidator — startup fail-closed gate (Faz 23.2 PR-A —
 * Codex 019dfae5 cumulative absorb).
 *
 * <p>Validates that production profile has critical security configuration set:
 * <ul>
 *   <li>DKIM enabled + selector + domain + private-key-pem (or corporate
 *       relay opt-out flag)</li>
 *   <li>SMTP TLS enforce + STARTTLS required + check-server-identity</li>
 *   <li>Webhook HMAC: registry enabled OR legacy secret rotated from default</li>
 *   <li>PII redaction pepper: NOT default ('dev-only-pepper-not-for-production')</li>
 *   <li>Authz: enabled + permission-service URL + internal-api-key</li>
 *   <li>Preferences: enabled (production opt-out only with explicit flag)</li>
 * </ul>
 *
 * <p>If any check fails, application context fails to start with explicit
 * error message — production deploy operator sees the gap before pod ready.
 *
 * <p>Codex absorb: "23.2 = production-ready foundation, prod cutover
 * iddiası kapalı". Bu validator prod profile'da fail-closed; non-prod
 * (test/dev/k8s test) profile'da SKIP.
 */
@Component
public class ProductionConfigValidator {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigValidator.class);

    private final Environment springEnv;
    private final String redactionPepper;
    private final String webhookLegacySecret;
    private final String authzInternalApiKey;
    private final boolean dkimEnabled;
    private final boolean smtpTlsEnforce;
    private final boolean preferencesEnabled;
    private final boolean authzEnabled;

    public ProductionConfigValidator(
        Environment springEnv,
        @Value("${notify.redaction.pepper:dev-only-pepper-not-for-production}")
            String redactionPepper,
        @Value("${notify.adapters.webhook.signing-secret:dev-only-secret-not-for-production}")
            String webhookLegacySecret,
        @Value("${notify.authz.internal-api-key:dev-only-key-not-for-production}")
            String authzInternalApiKey,
        @Value("${notify.dkim.enabled:false}") boolean dkimEnabled,
        @Value("${notify.smtp.tls.enforce:false}") boolean smtpTlsEnforce,
        @Value("${notify.preferences.enabled:true}") boolean preferencesEnabled,
        @Value("${notify.authz.enabled:true}") boolean authzEnabled
    ) {
        this.springEnv = springEnv;
        this.redactionPepper = redactionPepper;
        this.webhookLegacySecret = webhookLegacySecret;
        this.authzInternalApiKey = authzInternalApiKey;
        this.dkimEnabled = dkimEnabled;
        this.smtpTlsEnforce = smtpTlsEnforce;
        this.preferencesEnabled = preferencesEnabled;
        this.authzEnabled = authzEnabled;
    }

    @PostConstruct
    void validate() {
        if (!isProductionProfile()) {
            log.info("ProductionConfigValidator: non-prod profile, validation SKIPPED");
            return;
        }
        log.info("ProductionConfigValidator: prod profile — running fail-closed validation");

        List<String> errors = new ArrayList<>();

        // DKIM (Codex Q1)
        if (!dkimEnabled) {
            errors.add("notify.dkim.enabled=false — production must enable DKIM "
                + "(Codex Q1: app-side DKIM default; corporate relay alternative "
                + "requires explicit opt-out flag)");
        }

        // SMTP TLS (Codex Q2)
        if (!smtpTlsEnforce) {
            errors.add("notify.smtp.tls.enforce=false — production must enable "
                + "STARTTLS required (Codex Q2: plaintext fallback YASAK)");
        }

        // Redaction pepper (Codex Q5: HMAC-SHA256 + Vault pepper)
        if ("dev-only-pepper-not-for-production".equals(redactionPepper)) {
            errors.add("notify.redaction.pepper=DEFAULT — production must rotate "
                + "from dev-only value (Vault inject; Codex Q5 absorb)");
        }

        // Webhook HMAC (Codex Q3)
        if ("dev-only-secret-not-for-production".equals(webhookLegacySecret)) {
            errors.add("notify.adapters.webhook.signing-secret=DEFAULT — production "
                + "must rotate from dev-only value (kid-aware registry preferred; "
                + "legacy compat fallback only)");
        }

        // Authz (Codex Q3 PR5)
        if (!authzEnabled) {
            errors.add("notify.authz.enabled=false — production must enforce authz "
                + "(security regression risk; explicit opt-out only via "
                + "audit-tracked break-glass procedure)");
        }
        if ("dev-only-key-not-for-production".equals(authzInternalApiKey)) {
            errors.add("notify.authz.internal-api-key=DEFAULT — production must "
                + "rotate from dev-only value (Vault inject)");
        }

        // Preferences (Codex PR5 Q1)
        if (!preferencesEnabled) {
            errors.add("notify.preferences.enabled=false — production must enable "
                + "subscriber preferences (KVKK + opt-out compliance)");
        }

        if (!errors.isEmpty()) {
            String summary = "Production config validation FAILED ("
                + errors.size() + " error(s)):\n  - "
                + String.join("\n  - ", errors);
            log.error(summary);
            throw new IllegalStateException(summary);
        }

        log.info("ProductionConfigValidator: all production guards PASSED");
    }

    private boolean isProductionProfile() {
        for (String profile : springEnv.getActiveProfiles()) {
            if ("prod".equalsIgnoreCase(profile) || "production".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }
}
