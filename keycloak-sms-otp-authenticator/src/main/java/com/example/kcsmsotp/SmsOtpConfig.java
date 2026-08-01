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
    static final String CFG_GRANT_URL = "auth-grant-url";
    static final String CFG_INTENT_URL = "notify-intent-url";
    static final String CFG_CLIENT_ID = "client-id";
    static final String CFG_ORG_ID = "org-id";
    static final String CFG_TOPIC_KEY = "topic-key";
    static final String CFG_TEMPLATE_ID = "template-id";
    static final String CFG_TTL_SECONDS = "code-ttl-seconds";
    static final String CFG_MAX_ATTEMPTS = "max-attempts";
    static final String CFG_MAX_RESENDS = "max-resends";
    static final String CFG_PHONE_ATTRIBUTE = "phone-attribute";
    static final String CFG_CHANNEL = "delivery-channel";
    static final String CFG_METHODS_ATTRIBUTE = "methods-attribute";

    static final String CHANNEL_SMS = "sms";
    static final String CHANNEL_EMAIL = "email";

    final String tokenUrl;
    final String grantUrl;
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
    /** notify delivery channel: {@value #CHANNEL_SMS} or {@value #CHANNEL_EMAIL}. */
    final String channel;
    /** User attribute holding the per-user allow-list of channels. */
    final String methodsAttribute;

    private SmsOtpConfig(Map<String, String> cfg, Function<String, String> env) {
        this.tokenUrl = value(cfg, CFG_TOKEN_URL, "");
        // Derived from the token URL by default: the grant lives on the same
        // auth-service, and one fewer knob is one fewer way to point half the
        // chain at the wrong host.
        this.grantUrl = value(cfg, CFG_GRANT_URL,
                this.tokenUrl.isBlank() ? "" : this.tokenUrl.replaceFirst("/token$", "/mfa-delivery-grant"));
        this.intentUrl = value(cfg, CFG_INTENT_URL, "");
        this.clientId = value(cfg, CFG_CLIENT_ID, "keycloak-sms-otp");
        this.secret = env.apply(SECRET_ENV) == null ? "" : env.apply(SECRET_ENV);
        this.orgId = value(cfg, CFG_ORG_ID, "platform-system");
        // Read the channel first: the topic/template defaults follow it, so a
        // deployment only has to name the channel to get a coherent set.
        String channelForDefaults = CHANNEL_EMAIL.equalsIgnoreCase(
                value(cfg, CFG_CHANNEL, CHANNEL_SMS)) ? CHANNEL_EMAIL : CHANNEL_SMS;
        this.topicKey = value(cfg, CFG_TOPIC_KEY, "auth.mfa." + channelForDefaults + "-otp");
        this.templateId = value(cfg, CFG_TEMPLATE_ID, "auth." + channelForDefaults + "-otp");
        this.ttlSeconds = intValue(cfg, CFG_TTL_SECONDS, 300);
        this.maxAttempts = intValue(cfg, CFG_MAX_ATTEMPTS, 3);
        this.maxResends = intValue(cfg, CFG_MAX_RESENDS, 2);
        this.phoneAttribute = value(cfg, CFG_PHONE_ATTRIBUTE, "phoneNumber");
        // Defaults to SMS so an existing execution's config, written before
        // this key existed, keeps behaving exactly as it did.
        String ch = value(cfg, CFG_CHANNEL, CHANNEL_SMS);
        this.channel = CHANNEL_EMAIL.equalsIgnoreCase(ch) ? CHANNEL_EMAIL : CHANNEL_SMS;
        this.methodsAttribute = value(cfg, CFG_METHODS_ATTRIBUTE, "mfaMethods");
    }

    boolean isEmail() {
        return CHANNEL_EMAIL.equals(channel);
    }

    /**
     * The notify recipient key. notify's external recipient is
     * {@code {type: external, phone|email: …}} — the key names the channel,
     * so it moves with it.
     */
    String recipientKey() {
        return isEmail() ? "email" : "phone";
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
