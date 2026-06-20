package com.example.audiogateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Audio-gateway resource-server authorization hardening (Faz 24, platform-backend#716).
 *
 * <p>Shared Keycloak realm: any valid realm token currently passes issuer+signature
 * validation. These properties add audience + capability-role enforcement so only
 * tokens minted FOR audio-gateway by an authorized recorder client are accepted.
 *
 * <p><b>DEFAULT-OFF</b> (migration-safe, cross-AI consensus 019ee16b): both
 * {@code enforceAudience} and {@code requireAudioRecordRole} default {@code false}.
 * Flip to {@code true} ONLY after the audience+role protocol mappers are deployed to
 * all legit recorder clients and new-login + refresh-grant tokens are verified to
 * carry {@code aud=<resourceClientId>} + {@code resource_access.<resourceClientId>.roles=[<audioRecordRole>]}
 * (runbook steps 1-3, already DONE for platform-desktop). Enforce-first would 401
 * existing in-flight tokens.
 */
@ConfigurationProperties(prefix = "audio-gateway.security")
public class AudioGatewaySecurityProperties {

    /** Keycloak resource client id = required {@code aud} value + {@code resource_access} key for roles. */
    private String resourceClientId = "audio-gateway-service";

    /** When true, the JWT {@code aud} claim MUST contain {@link #resourceClientId}. Default-off. */
    private boolean enforceAudience = false;

    /** When true, protected exchanges require the {@link #audioRecordRole} authority. Default-off. */
    private boolean requireAudioRecordRole = false;

    /** Capability role name inside {@code resource_access.<resourceClientId>.roles}. */
    private String audioRecordRole = "audio_record";

    public String getResourceClientId() {
        return resourceClientId;
    }

    public void setResourceClientId(final String resourceClientId) {
        this.resourceClientId = resourceClientId;
    }

    public boolean isEnforceAudience() {
        return enforceAudience;
    }

    public void setEnforceAudience(final boolean enforceAudience) {
        this.enforceAudience = enforceAudience;
    }

    public boolean isRequireAudioRecordRole() {
        return requireAudioRecordRole;
    }

    public void setRequireAudioRecordRole(final boolean requireAudioRecordRole) {
        this.requireAudioRecordRole = requireAudioRecordRole;
    }

    public String getAudioRecordRole() {
        return audioRecordRole;
    }

    public void setAudioRecordRole(final String audioRecordRole) {
        this.audioRecordRole = audioRecordRole;
    }
}
