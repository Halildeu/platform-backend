package com.serban.notify.authz;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.util.ReflectionTestUtils;

import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.domain.NotificationIntent;

/**
 * The verifier's job is to REFUSE: a grant authorises exactly one delivery,
 * so every field it names must match the intent it arrived with. These tests
 * walk each field individually, because a verifier that checks "most" fields
 * is a verifier that can be pointed at a different recipient.
 */
class MfaDeliveryGrantVerifierTest {

    private MfaDeliveryGrantVerifier verifier;
    private StubDecoder decoder;

    /** Minimal decoder stub: returns a prepared Jwt, or throws for "bad". */
    private static final class StubDecoder implements JwtDecoder {
        Jwt next;
        @Override public Jwt decode(String token) {
            if ("bad".equals(token)) {
                throw new JwtException("signature");
            }
            return next;
        }
    }

    private static Jwt jwt(Map<String, Object> claims) {
        return new Jwt("token", Instant.now(), Instant.now().plusSeconds(120),
                Map.of("alg", "RS256"), claims);
    }

    private Map<String, Object> validClaims() {
        return new java.util.HashMap<>(Map.of(
                "iss", "auth-service",
                "sub", "kc-user-1",
                "jti", "grant-1",
                "purpose", "mfa_otp",
                "topic", "auth.mfa.sms-otp",
                "template", "auth.sms-otp",
                "channel", "sms",
                "recipient", "+905321234567",
                "auth_session_id", "sess-1",
                "deliver_before", Instant.now().plusSeconds(600).getEpochSecond()));
    }

    private SubmitIntentRequest request() {
        return new SubmitIntentRequest(
                "intent-1", "idem-1", null, "platform-system", "auth.mfa.sms-otp",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.security,
                List.of(new SubmitIntentRequest.RecipientRef(
                        SubmitIntentRequest.RecipientRef.Type.external, null, null,
                        "+905321234567", null, "tr")),
                new SubmitIntentRequest.TemplateRef("auth.sms-otp", null, "tr"),
                List.of("sms"), Map.of("code", "123456"),
                null, null, null, null, null);
    }

    @BeforeEach
    void setUp() {
        verifier = new MfaDeliveryGrantVerifier("", "auth-service");
        decoder = new StubDecoder();
        ReflectionTestUtils.setField(verifier, "decoder", decoder);
    }

    private Optional<MfaDeliveryGrant> verifyWith(Map<String, Object> claims) {
        decoder.next = jwt(claims);
        return verifier.verify("token", request());
    }

    @Test
    void fullyMatchingGrant_isAccepted() {
        Optional<MfaDeliveryGrant> result = verifyWith(validClaims());
        assertThat(result).isPresent();
        assertThat(result.get().jti()).isEqualTo("grant-1");
        assertThat(result.get().subject()).isEqualTo("kc-user-1");
        assertThat(result.get().recipient()).isEqualTo("+905321234567");
    }

    @Test
    void noGrantHeader_isEmpty_soTheOrdinaryPathApplies() {
        assertThat(verifier.verify(null, request())).isEmpty();
        assertThat(verifier.verify("", request())).isEmpty();
    }

    @Test
    void badSignature_isRefused() {
        decoder.next = jwt(validClaims());
        assertThat(verifier.verify("bad", request())).isEmpty();
    }

    @Test
    void wrongIssuer_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("iss", "someone-else");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void wrongPurpose_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("purpose", "password_reset");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void missingJti_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.remove("jti");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void grantForAnotherRecipient_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("recipient", "+905000000000");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void grantForAnotherTemplate_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("template", "marketing.blast");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void grantForAnotherTopic_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("topic", "marketing.campaign");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void grantForAnotherChannel_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("channel", "email");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void closedDeliveryWindow_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.put("deliver_before", Instant.now().minusSeconds(1).getEpochSecond());
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void missingDeliverBefore_isRefused() {
        Map<String, Object> claims = validClaims();
        claims.remove("deliver_before");
        assertThat(verifyWith(claims)).isEmpty();
    }

    @Test
    void unconfiguredVerifier_isDisabledAndRefusesEverything() {
        MfaDeliveryGrantVerifier disabled = new MfaDeliveryGrantVerifier("", "auth-service");
        assertThat(disabled.isEnabled()).isFalse();
        assertThat(disabled.verify("token", request())).isEmpty();
    }

    // ── channel-aware recipient (gitops#3230, found live 2026-08-01) ─────

    private SubmitIntentRequest emailRequest() {
        return new SubmitIntentRequest(
                "intent-2", "idem-2", null, "platform-system", "auth.mfa.email-otp",
                NotificationIntent.Severity.info,
                NotificationIntent.DataClassification.security,
                List.of(new SubmitIntentRequest.RecipientRef(
                        SubmitIntentRequest.RecipientRef.Type.external, null, "ops@acik.com",
                        null, null, "tr")),
                new SubmitIntentRequest.TemplateRef("auth.email-otp", null, "tr"),
                List.of("email"), Map.of("code", "123456"),
                null, null, null, null, null);
    }

    private Map<String, Object> emailClaims() {
        return new java.util.HashMap<>(Map.of(
                "iss", "auth-service", "purpose", "mfa_otp", "jti", "grant-2",
                "client_id", "keycloak-sms-otp", "topic", "auth.mfa.email-otp",
                "template", "auth.email-otp", "channel", "email",
                "recipient", "ops@acik.com",
                "auth_session_id", "sess-2",
                "deliver_before", Instant.now().plusSeconds(600).getEpochSecond()));
    }

    @Test
    void emailGrant_matchesTheEmailRecipient_notThePhoneField() {
        // Live regression: the check read `phone` for every channel, so every
        // e-mail grant was rejected and the delivery fell back to the ordinary
        // authz path and was blocked. The endpoint was correct; the check was
        // looking at the wrong field.
        decoder.next = jwt(emailClaims());
        Optional<MfaDeliveryGrant> result = verifier.verify("token", emailRequest());

        assertThat(result).isPresent();
        assertThat(result.get().recipient()).isEqualTo("ops@acik.com");
    }

    @Test
    void emailGrantForAnotherAddress_isStillRefused() {
        Map<String, Object> claims = emailClaims();
        claims.put("recipient", "someone-else@acik.com");
        decoder.next = jwt(claims);

        assertThat(verifier.verify("token", emailRequest())).isEmpty();
    }

    @Test
    void aGrantCannotCrossChannels_evenWithMatchingText() {
        // An SMS grant naming an address must not authorise an e-mail intent:
        // the channel is compared first, and the recipient is then read from
        // the field that channel actually uses.
        Map<String, Object> claims = emailClaims();
        claims.put("channel", "sms");
        decoder.next = jwt(claims);

        assertThat(verifier.verify("token", emailRequest())).isEmpty();
    }

    @Test
    void anUnknownChannelResolvesToNoRecipient_soItFailsClosed() {
        Map<String, Object> claims = emailClaims();
        claims.put("channel", "push");
        decoder.next = jwt(claims);

        assertThat(verifier.verify("token", emailRequest())).isEmpty();
    }
}
