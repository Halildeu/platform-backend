package com.serban.notify.authz;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import com.serban.notify.api.dto.SubmitIntentRequest;

/**
 * Verifies the one-shot MFA delivery grant against the intent it accompanies
 * (gitops#3212, design: Codex 019fb825).
 *
 * <p>The grant is checked at the TRUST BOUNDARY — the submit request — and
 * the derived evidence is persisted; the asynchronous dispatch worker never
 * sees the JWT. Verification is exact on every field the grant claims to
 * authorise: a grant for another template, another channel, another topic or
 * another recipient authorises nothing here.
 *
 * <p>Disabled by default: without a configured JWK-set URI this returns
 * empty, and the caller treats the delivery as ordinary (i.e. subject to the
 * full recipient authz check). A missing configuration must never widen
 * anything.
 */
@Component
public class MfaDeliveryGrantVerifier {

    private static final Logger log = LoggerFactory.getLogger(MfaDeliveryGrantVerifier.class);
    private static final String PURPOSE = "mfa_otp";

    private final JwtDecoder decoder;
    private final String expectedIssuer;

    public MfaDeliveryGrantVerifier(
            @Value("${notify.internal.service-jwt.jwk-set-uri:${NOTIFY_INTERNAL_SERVICE_JWT_JWK_SET_URI:}}")
            String jwkSetUri,
            @Value("${notify.internal.service-jwt.issuer:${NOTIFY_INTERNAL_SERVICE_JWT_ISSUER:auth-service}}")
            String expectedIssuer) {
        this.expectedIssuer = expectedIssuer;
        this.decoder = StringUtils.hasText(jwkSetUri)
                ? NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build()
                : null;
    }

    public boolean isEnabled() {
        return decoder != null;
    }

    /**
     * @return the verified grant, or empty when there is no usable grant. The
     *         caller must then fall back to the ordinary authz path.
     */
    public java.util.Optional<MfaDeliveryGrant> verify(String rawGrant, SubmitIntentRequest request) {
        if (decoder == null || !StringUtils.hasText(rawGrant)) {
            return java.util.Optional.empty();
        }
        Jwt jwt;
        try {
            jwt = decoder.decode(rawGrant);
        } catch (JwtException e) {
            log.warn("mfa grant rejected: signature/exp invalid ({})", e.getMessage());
            return java.util.Optional.empty();
        }

        // getIssuer() coerces the claim to a URL and throws for a plain name;
        // auth-service issues `iss: auth-service`, so read it as a string.
        if (!expectedIssuer.equals(jwt.getClaimAsString("iss"))) {
            log.warn("mfa grant rejected: unexpected issuer");
            return java.util.Optional.empty();
        }
        if (!PURPOSE.equals(jwt.getClaimAsString("purpose"))) {
            log.warn("mfa grant rejected: purpose is not {}", PURPOSE);
            return java.util.Optional.empty();
        }
        String jti = jwt.getId();
        if (!StringUtils.hasText(jti)) {
            log.warn("mfa grant rejected: no jti");
            return java.util.Optional.empty();
        }

        // Exact binding to THIS intent. Anything less would let a grant issued
        // for one delivery authorise a different one.
        String topic = jwt.getClaimAsString("topic");
        String template = jwt.getClaimAsString("template");
        String channel = jwt.getClaimAsString("channel");
        String recipient = jwt.getClaimAsString("recipient");

        if (!equalsExact(topic, request.topicKey())
                || !equalsExact(template, request.template() == null ? null : request.template().templateId())) {
            log.warn("mfa grant rejected: topic/template does not match the intent");
            return java.util.Optional.empty();
        }
        List<String> channels = request.channels() == null ? List.of() : request.channels();
        if (channels.size() != 1 || !equalsExact(channel, channels.get(0))) {
            log.warn("mfa grant rejected: intent must carry exactly the granted channel");
            return java.util.Optional.empty();
        }
        List<SubmitIntentRequest.RecipientRef> recipients =
                request.recipients() == null ? List.of() : request.recipients();
        // The recipient field follows the channel: `phone` for SMS, `email`
        // for mail. Comparing against `phone` for every channel rejected every
        // e-mail grant — the delivery then fell back to the ordinary authz
        // path and was blocked, which reads as "the feature does not work"
        // rather than "the check is looking at the wrong field" (measured on
        // k3d-test 2026-08-01: `mfa grant rejected: intent must carry exactly
        // the granted recipient`, then BLOCKED_BY_AUTHZ).
        if (recipients.size() != 1
                || !equalsExact(recipient, recipientForChannel(recipients.get(0), channel))) {
            log.warn("mfa grant rejected: intent must carry exactly the granted recipient");
            return java.util.Optional.empty();
        }

        Long deliverBeforeEpoch = jwt.getClaim("deliver_before") instanceof Number n
                ? n.longValue() : null;
        if (deliverBeforeEpoch == null) {
            log.warn("mfa grant rejected: no deliver_before");
            return java.util.Optional.empty();
        }
        Instant deliverBefore = Instant.ofEpochSecond(deliverBeforeEpoch);
        if (Instant.now().isAfter(deliverBefore)) {
            log.warn("mfa grant rejected: delivery window already closed");
            return java.util.Optional.empty();
        }

        return java.util.Optional.of(new MfaDeliveryGrant(
                jti, jwt.getSubject(), recipient, channel, topic, template,
                jwt.getClaimAsString("auth_session_id"), deliverBefore));
    }

    /**
     * An unknown channel resolves to null, so the exact-match check below
     * fails closed: a channel we cannot read the recipient for must not be
     * treated as matching.
     */
    private static String recipientForChannel(SubmitIntentRequest.RecipientRef ref, String channel) {
        if (ref == null || channel == null) {
            return null;
        }
        return switch (channel) {
            case "sms" -> ref.phone();
            case "email" -> ref.email();
            default -> null;
        };
    }

    private static boolean equalsExact(String a, String b) {
        return a != null && a.equals(b);
    }
}
