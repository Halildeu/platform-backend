package com.example.kcsmsotp;

import java.util.Map;
import java.util.function.Function;

import org.keycloak.models.AuthenticatorConfigModel;

/**
 * Per-execution authenticator configuration. Everything non-secret lives in
 * the flow's authenticator config (visible and editable in the admin console,
 * set by scripts/keycloak/setup-privileged-mfa.sh in platform-k8s-gitops).
 * The client secret deliberately does NOT: Keycloak persists authenticator
 * config plaintext in its DB, so the secret comes only from the container
 * environment ({@link #SECRET_ENV}, docker-secret file exported by the
 * compose entrypoint wrapper).
 */
final class SmsOtpConfig {

    static final String SECRET_ENV = "SMS_OTP_SERVICE_CLIENT_SECRET";

    static final String CFG_TOKEN_URL = "auth-token-url";
    static final String CFG_INTENT_URL = "notify-intent-url";
    static final String CFG_CLIENT_ID = "client-id";
    static final String CFG_ORG_ID = "org-id";
    static final String CFG_TOPIC_KEY = "topic-key";
    static final String CFG_TEMPLATE_ID = "template-id";
    static final String CFG_TTL_SECONDS = "code-ttl-seconds";
    static final String CFG_MAX_ATTEMPTS = "max-attempts";
    static final String CFG_MAX_RESENDS = "max-resends";
    static final String CFG_PHONE_ATTRIBUTE = "phone-attribute";

    final String tokenUrl;
    final String intentUrl;
    final String clientId;
    final String secret;
    final String orgId;
    final String topicKey;
    final String templateId;
    final int ttlSeconds;
    final int maxAttempts;
    final int maxResends;
    final String phoneAttribute;

    private SmsOtpConfig(Map<String, String> cfg, Function<String, String> env) {
        this.tokenUrl = value(cfg, CFG_TOKEN_URL, "");
        this.intentUrl = value(cfg, CFG_INTENT_URL, "");
        this.clientId = value(cfg, CFG_CLIENT_ID, "keycloak-sms-otp");
        this.secret = env.apply(SECRET_ENV) == null ? "" : env.apply(SECRET_ENV);
        this.orgId = value(cfg, CFG_ORG_ID, "platform-system");
        this.topicKey = value(cfg, CFG_TOPIC_KEY, "auth.mfa.sms-otp");
        this.templateId = value(cfg, CFG_TEMPLATE_ID, "auth.sms-otp");
        this.ttlSeconds = intValue(cfg, CFG_TTL_SECONDS, 300);
        this.maxAttempts = intValue(cfg, CFG_MAX_ATTEMPTS, 3);
        this.maxResends = intValue(cfg, CFG_MAX_RESENDS, 2);
        this.phoneAttribute = value(cfg, CFG_PHONE_ATTRIBUTE, "phoneNumber");
    }

    static SmsOtpConfig from(AuthenticatorConfigModel model, Function<String, String> env) {
        Map<String, String> cfg = model == null || model.getConfig() == null
                ? Map.of()
                : model.getConfig();
        return new SmsOtpConfig(cfg, env);
    }

    /**
     * Fail-closed sendability: without URLs and a provisioned secret the
     * authenticator must not offer itself as an alternative at all.
     */
    boolean isSendable() {
        return !tokenUrl.isBlank() && !intentUrl.isBlank() && !secret.isBlank();
    }

    private static String value(Map<String, String> cfg, String key, String fallback) {
        String raw = cfg.get(key);
        return raw == null || raw.isBlank() ? fallback : raw.trim();
    }

    private static int intValue(Map<String, String> cfg, String key, int fallback) {
        try {
            return Integer.parseInt(value(cfg, key, Integer.toString(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
