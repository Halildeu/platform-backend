package com.serban.notify.authz;

import java.time.Instant;

/**
 * The verified evidence derived from an MFA delivery grant (gitops#3212).
 *
 * <p>Only this derived record is persisted — never the raw JWT. It answers
 * exactly one question for the asynchronous dispatch worker: was this ONE
 * delivery, to this recipient, under this template, authorised at submit
 * time by auth-service, and is that authorisation still inside its window?
 */
public record MfaDeliveryGrant(
        String jti,
        String subject,
        String recipient,
        String channel,
        String topic,
        String template,
        String authSessionId,
        Instant deliverBefore) {
}
