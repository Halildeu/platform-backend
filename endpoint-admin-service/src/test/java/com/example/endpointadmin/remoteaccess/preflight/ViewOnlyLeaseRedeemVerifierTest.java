package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ViewOnlyLeaseRedeemVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REF = "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String D1 = "sha256:" + "1".repeat(64);

    private RemoteViewJsonCanonicalizer canonicalizer;
    private ObjectNode binding;
    private String bindingDigest;
    private String transactionDigest;
    private VerifiedViewOnlyPreflightReceipt evaluation;
    private VerifiedViewOnlyAuthorization authorization;
    private ViewOnlyLeaseRedeemVerifier verifier;

    @BeforeEach
    void setUp() {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        binding = ViewOnlyTestFixtures.binding(canonicalizer, 186576227L, 29678094664L);
        ViewOnlyDigest digest = new ViewOnlyDigest(canonicalizer);
        bindingDigest = digest.domainDigest(
                "faz22.6/view-only/transaction-binding/v1", "binding", binding);
        transactionDigest = digest.domainDigest(
                "faz22.6/view-only/transaction-id/v1", "binding", binding);
        evaluation = new VerifiedViewOnlyPreflightReceipt(
                D1, bindingDigest, transactionDigest,
                NOW.minusSeconds(600), NOW.minusSeconds(300), 0, false);
        ObjectNode authorizationEnvelope = canonicalizer.mapper().createObjectNode().put("kind", "a");
        String authorizationEnvelopeDigest = digest.domainDigest(
                "faz22.6/view-only/dsse-envelope/v1", "envelope", authorizationEnvelope);
        authorization = new VerifiedViewOnlyAuthorization(
                authorizationEnvelopeDigest, VerifiedViewOnlyAuthorization.PAYLOAD_TYPE,
                bindingDigest, transactionDigest,
                NOW.minusSeconds(60), NOW.plusSeconds(1800), false);
        verifier = verifier(evaluation, authorization);
    }

    @Test
    void acceptsExpiredEvaluationInsideBoundedHumanWait() {
        ObjectNode request = request();
        setIdempotency(request);
        VerifiedViewOnlyLeaseRedeem verified = verifier.verify(
                canonicalizer.canonicalBytes(request), authorizationJwt());

        assertThat(verified.command().bindingSha256()).isEqualTo(bindingDigest);
        assertThat(verified.command().transactionIdSha256()).isEqualTo(transactionDigest);
        assertThat(verified.command().requestedMaxWrites()).isEqualTo(64);
        assertThat(verified.caller().profile()).isEqualTo("authorization");
    }

    @Test
    void rejectsStaleEvaluationAndMismatchedReceiptBinding() {
        VerifiedViewOnlyPreflightReceipt stale = new VerifiedViewOnlyPreflightReceipt(
                D1, bindingDigest, transactionDigest,
                NOW.minusSeconds(7201), NOW.minusSeconds(7000), 0, false);
        ObjectNode request = request();
        setIdempotency(request);
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier(stale, authorization).verify(
                        canonicalizer.canonicalBytes(request), authorizationJwt()));

        VerifiedViewOnlyAuthorization mismatch = new VerifiedViewOnlyAuthorization(
                D1, VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, D1, transactionDigest,
                NOW.minusSeconds(60), NOW.plusSeconds(1800), false);
        assertReason(ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH,
                () -> verifier(evaluation, mismatch).verify(
                        canonicalizer.canonicalBytes(request), authorizationJwt()));
    }

    @Test
    void rejectsWrongIdempotencyAndWrongProtectedRun() {
        ObjectNode wrongKey = request();
        wrongKey.put("idempotencyKeySha256", D1);
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(canonicalizer.canonicalBytes(wrongKey), authorizationJwt()));

        ObjectNode correct = request();
        setIdempotency(correct);
        Jwt wrongRun = authorizationJwt("29678094665");
        assertReason(ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH,
                () -> verifier.verify(canonicalizer.canonicalBytes(correct), wrongRun));
    }

    @Test
    void retryCandidateDoesNotReapplyExpiredAuthorityAfterExactRequestVerification() {
        VerifiedViewOnlyAuthorization expired = new VerifiedViewOnlyAuthorization(
                authorization.envelopeSha256(), VerifiedViewOnlyAuthorization.PAYLOAD_TYPE,
                bindingDigest, transactionDigest, NOW.minusSeconds(1800), NOW.minusSeconds(1), false);
        ObjectNode request = request();
        setIdempotency(request);

        ViewOnlyLeaseRetryCandidate candidate = verifier(evaluation, expired).retryCandidate(
                canonicalizer.canonicalBytes(request), authorizationJwt());

        assertThat(candidate.requestId().toString())
                .isEqualTo("123e4567-e89b-42d3-a456-426614174002");
        assertThat(candidate.authorizationEnvelopeSha256()).isEqualTo(authorization.envelopeSha256());
        assertReason(ViewOnlyAuthorityError.AUTHORIZATION_EXPIRED,
                () -> verifier(evaluation, expired).verify(
                        canonicalizer.canonicalBytes(request), authorizationJwt()));
    }

    @Test
    void rejectsUnknownOrCredentialLikeBindingFields() {
        ObjectNode request = request();
        ObjectNode unsafeBinding = ((ObjectNode) request.get("binding")).deepCopy();
        unsafeBinding.put("credential", "must-never-cross-authority-boundary");
        request.set("binding", unsafeBinding);
        ViewOnlyDigest digest = new ViewOnlyDigest(canonicalizer);
        request.put("bindingSha256", digest.domainDigest(
                "faz22.6/view-only/transaction-binding/v1", "binding", unsafeBinding));
        request.put("transactionIdSha256", digest.domainDigest(
                "faz22.6/view-only/transaction-id/v1", "binding", unsafeBinding));
        setIdempotency(request);

        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(canonicalizer.canonicalBytes(request), authorizationJwt()));
    }

    private ViewOnlyLeaseRedeemVerifier verifier(VerifiedViewOnlyPreflightReceipt preflight,
                                                 VerifiedViewOnlyAuthorization auth) {
        return new ViewOnlyLeaseRedeemVerifier(
                canonicalizer, (envelope, now) -> preflight, (envelope, binding, now) -> auth,
                new ViewOnlyOidcCallerFactory(
                        canonicalizer, ViewOnlyAuthorityProperties.CANONICAL_OIDC_JTI_DIGEST_DOMAIN),
                CLOCK);
    }

    private ObjectNode request() {
        ObjectNode request = canonicalizer.mapper().createObjectNode();
        request.put("schemaVersion", "faz22.6.viewOnlyCheckpointLeaseRedeem.v1");
        request.put("requestId", "123e4567-e89b-42d3-a456-426614174002");
        request.put("idempotencyKeySha256", D1);
        request.set("binding", binding.deepCopy());
        request.put("bindingSha256", bindingDigest);
        request.put("transactionIdSha256", transactionDigest);
        request.set("evaluationPreflightReceiptEnvelope", canonicalizer.mapper().createObjectNode().put("kind", "p"));
        request.set("authorizationEnvelope", canonicalizer.mapper().createObjectNode().put("kind", "a"));
        request.put("authorizationPayloadType", VerifiedViewOnlyAuthorization.PAYLOAD_TYPE);
        request.put("requestedTtlSeconds", 900);
        request.put("requestedMaxWrites", 64);
        return request;
    }

    private void setIdempotency(ObjectNode request) {
        ObjectNode withoutKey = request.deepCopy();
        withoutKey.remove("idempotencyKeySha256");
        String bodyDigest = canonicalizer.digest(withoutKey);
        ViewOnlyOidcCaller caller = new ViewOnlyOidcCallerFactory(
                canonicalizer, ViewOnlyAuthorityProperties.CANONICAL_OIDC_JTI_DIGEST_DOMAIN).create(
                authorizationJwt(), ViewOnlyGithubOidcProfile.AUTHORIZATION,
                new ViewOnlyOidcBinding(186576227L, 29678094664L, 1, REF, SHA));
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", "faz22.6/view-only/checkpoint-lease-redeem-idempotency/v1");
        projection.put("requestId", request.get("requestId").textValue());
        projection.put("bodySha256", bodyDigest);
        projection.set("identity", caller.stableIdentityProjection(canonicalizer));
        request.put("idempotencyKeySha256", canonicalizer.digest(projection));
    }

    private static Jwt authorizationJwt() {
        return authorizationJwt("29678094664");
    }

    private static Jwt authorizationJwt(String runId) {
        return Jwt.withTokenValue("redacted")
                .header("alg", "RS256")
                .issuer("https://token.actions.githubusercontent.com")
                .subject("repo:Halildeu/platform-k8s-gitops:environment:faz22-view-only-pilot")
                .audience(List.of(ViewOnlyGithubOidcProfile.AUTHORIZATION.audience()))
                .issuedAt(NOW.minusSeconds(20))
                .notBefore(NOW.minusSeconds(20))
                .expiresAt(NOW.plusSeconds(280))
                .claim("actor_id", "186576227")
                .claim("repository_id", "1211415632")
                .claim("run_id", runId)
                .claim("run_attempt", "1")
                .claim("ref", REF)
                .claim("sha", SHA)
                .claim("jti", "raw-jti")
                .build();
    }

    private static void assertReason(ViewOnlyAuthorityError reason, Runnable call) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(reason);
    }
}
