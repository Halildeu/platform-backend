package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class ViewOnlyCheckpointCreateVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String REF = "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000";
    private static final String SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String DOMAIN = "test/checkpoint-create-idempotency/v1";
    private static final String DIGEST_A = "sha256:" + "a".repeat(64);
    private static final String DIGEST_B = "sha256:" + "b".repeat(64);
    private static final String DIGEST_C = "sha256:" + "c".repeat(64);
    private static final UUID LEASE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");

    private RemoteViewJsonCanonicalizer canonicalizer;
    private ViewOnlyOidcCallerFactory callerFactory;
    private VerifiedViewOnlyLeaseEnvelope lease;
    private ViewOnlyCheckpointCreateVerifier verifier;

    @BeforeEach
    void setUp() {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        callerFactory = new ViewOnlyOidcCallerFactory(canonicalizer, "test/jti/v1");
        ObjectNode binding = canonicalizer.mapper().createObjectNode().put("headSha", SHA);
        lease = new VerifiedViewOnlyLeaseEnvelope(
                LEASE_ID, DIGEST_A, DIGEST_B, DIGEST_C, binding,
                new ViewOnlyOidcBinding(186576227L, 29678094664L, 1, REF, SHA),
                DIGEST_A, DIGEST_B, DIGEST_C, NOW.plusSeconds(900), false, 0, 63, 64);
        verifier = new ViewOnlyCheckpointCreateVerifier(
                canonicalizer, (envelope, now) -> lease, callerFactory, CLOCK, DOMAIN);
    }

    @Test
    void verifiesInitialRequestAndDerivesServerDigests() {
        ObjectNode request = initialRequest();
        setCorrectIdempotency(request);

        VerifiedViewOnlyCheckpointCreate verified = verifier.verify(
                canonicalizer.canonicalBytes(request), executorJwt());

        assertThat(verified.command().leaseId()).isEqualTo(LEASE_ID);
        assertThat(verified.command().state()).isEqualTo(ViewOnlyCheckpointState.DECISION_AUTHORIZED);
        assertThat(verified.command().requestBodySha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(verified.command().storedObjectSha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(verified.caller().profile()).isEqualTo("executor");
    }

    @Test
    void rejectsUnknownFieldAndWrongIdempotencyDomainResult() {
        ObjectNode unknown = initialRequest();
        unknown.put("callerVerdict", "PASS");
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(canonicalizer.canonicalBytes(unknown), executorJwt()));

        ObjectNode wrongKey = initialRequest();
        wrongKey.put("idempotencyKeySha256", DIGEST_A);
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(canonicalizer.canonicalBytes(wrongKey), executorJwt()));
    }

    @Test
    void rejectsMalformedUtf8AndOversizedBody() {
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(new byte[]{(byte) 0xC3, (byte) 0x28}, executorJwt()));
        assertReason(ViewOnlyAuthorityError.CONTRACT_INVALID,
                () -> verifier.verify(new byte[ViewOnlyCheckpointCreateVerifier.MAX_REQUEST_BYTES + 1], executorJwt()));
    }

    @Test
    void rejectsExpiredOrClosedVerifiedLease() {
        VerifiedViewOnlyLeaseEnvelope expired = new VerifiedViewOnlyLeaseEnvelope(
                LEASE_ID, DIGEST_A, DIGEST_B, DIGEST_C,
                canonicalizer.mapper().createObjectNode(), lease.oidcBinding(),
                DIGEST_A, DIGEST_B, DIGEST_C, NOW.minusSeconds(1), false, 0, 63, 64);
        ViewOnlyCheckpointCreateVerifier expiredVerifier = new ViewOnlyCheckpointCreateVerifier(
                canonicalizer, (envelope, now) -> expired, callerFactory, CLOCK, DOMAIN);
        ObjectNode request = initialRequest();
        setCorrectIdempotency(request);
        assertReason(ViewOnlyAuthorityError.LEASE_EXPIRED,
                () -> expiredVerifier.verify(canonicalizer.canonicalBytes(request), executorJwt()));
    }

    @Test
    void constructorFailsClosedWithoutCanonicalCreateDomain() {
        assertThatThrownBy(() -> new ViewOnlyCheckpointCreateVerifier(
                canonicalizer, (envelope, now) -> lease, callerFactory, CLOCK, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private ObjectNode initialRequest() {
        ObjectNode request = canonicalizer.mapper().createObjectNode();
        request.put("schemaVersion", "faz22.6.viewOnlyExternalCheckpointCreate.v1");
        request.put("requestId", "123e4567-e89b-42d3-a456-426614174002");
        request.set("leaseEnvelope", canonicalizer.mapper().createObjectNode()
                .put("payloadType", "application/vnd.acik.faz22-6-view-only-checkpoint-lease.v1+json"));
        request.put("transactionIdSha256", DIGEST_B);
        request.put("bindingSha256", DIGEST_C);
        request.put("sequence", 0);
        request.putNull("previousState");
        request.put("state", "DECISION_AUTHORIZED");
        request.put("reasonCode", "decision-authorized");
        request.put("localCheckpointSha256", DIGEST_A);
        request.put("localPayloadSha256", DIGEST_B);
        request.putNull("previousStoredObjectSha256");
        request.put("idempotencyKeySha256", DIGEST_C);
        request.put("terminal", false);
        return request;
    }

    private void setCorrectIdempotency(ObjectNode request) {
        ObjectNode withoutKey = request.deepCopy();
        withoutKey.remove("idempotencyKeySha256");
        String bodyDigest = canonicalizer.digest(withoutKey);
        ViewOnlyOidcCaller caller = callerFactory.create(
                executorJwt(), ViewOnlyGithubOidcProfile.EXECUTOR, lease.oidcBinding());
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", DOMAIN);
        projection.put("requestId", request.get("requestId").textValue());
        projection.put("bodySha256", bodyDigest);
        projection.set("identity", caller.stableIdentityProjection(canonicalizer));
        request.put("idempotencyKeySha256", canonicalizer.digest(projection));
    }

    private static Jwt executorJwt() {
        return Jwt.withTokenValue("redacted")
                .header("alg", "RS256")
                .issuer("https://token.actions.githubusercontent.com")
                .subject("repo:Halildeu/platform-k8s-gitops:ref:" + REF)
                .audience(List.of(ViewOnlyGithubOidcProfile.EXECUTOR.audience()))
                .issuedAt(NOW.minusSeconds(20))
                .notBefore(NOW.minusSeconds(20))
                .expiresAt(NOW.plusSeconds(280))
                .claim("actor_id", "186576227")
                .claim("repository_id", "1211415632")
                .claim("run_id", "29678094664")
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
