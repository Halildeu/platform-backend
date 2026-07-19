package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ViewOnlyReceiptPayloadFactoryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String D1 = "sha256:" + "1".repeat(64);
    private static final String D2 = "sha256:" + "2".repeat(64);
    private static final String D3 = "sha256:" + "3".repeat(64);
    private static final String D4 = "sha256:" + "4".repeat(64);
    private static final String D5 = "sha256:" + "5".repeat(64);
    private static final String D6 = "sha256:" + "6".repeat(64);
    private static final UUID LEASE_ID = UUID.fromString("123e4567-e89b-42d3-a456-426614174001");

    private RemoteViewJsonCanonicalizer canonicalizer;
    private ViewOnlyReceiptPayloadFactory factory;
    private ObjectNode binding;
    private ViewOnlyOidcCaller caller;

    @BeforeEach
    void setUp() {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        factory = new ViewOnlyReceiptPayloadFactory(canonicalizer);
        binding = canonicalizer.mapper().createObjectNode()
                .put("triggeringActorId", 1)
                .put("runId", 1)
                .put("runAttempt", 1)
                .put("intentRef", "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000")
                .put("headSha", "0".repeat(40));
        caller = new ViewOnlyOidcCaller(
                "authorization", "https://token.actions.githubusercontent.com",
                "repo:Halildeu/platform-k8s-gitops:environment:faz22-view-only-pilot",
                1, 1, 1, 1,
                "refs/tags/cross-ai-intent/123e4567-e89b-42d3-a456-426614174000",
                "0".repeat(40), D1);
    }

    @Test
    void leasePayloadHasExactSchemaFieldSet() {
        VerifiedViewOnlyPreflightReceipt evaluation = receipt(D1, NOW.minusSeconds(60));
        VerifiedViewOnlyPreflightReceipt refreshed = receipt(D2, NOW.minusSeconds(5));
        VerifiedViewOnlyAuthorization authorization = new VerifiedViewOnlyAuthorization(
                D3, VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, D4, D5,
                NOW.minusSeconds(60), NOW.plusSeconds(1800), false);
        ViewOnlyLeaseRedeemCommand command = new ViewOnlyLeaseRedeemCommand(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174002"), D6, D1, D2,
                binding, D4, D5, evaluation, authorization, 900, 64);
        ObjectNode payload = factory.lease(new ViewOnlyLeaseSigningInput(
                LEASE_ID, command, caller, refreshed, NOW, NOW.plusSeconds(900)));

        assertThat(fieldNames(payload)).isEqualTo(Set.of(
                "schemaVersion", "leaseId", "redeemRequestId", "idempotencyKeySha256",
                "transactionIdSha256", "bindingSha256", "binding",
                "evaluationPreflightReceiptEnvelopeSha256", "redemptionPreflightReceiptEnvelopeSha256",
                "redemptionPreflightIssuedAt", "authorizationEnvelopeSha256", "authorizationPayloadType",
                "authorizationRedemptionCount", "authorizationCaller", "executorProfile", "issuedAt",
                "expiresAt", "sequenceMinimumInclusive", "sequenceMaximumInclusive", "maxWrites", "closed"));
        assertThat(payload.get("authorizationRedemptionCount").intValue()).isEqualTo(1);
        assertThat(payload.get("closed").booleanValue()).isFalse();
    }

    @Test
    void checkpointPayloadHasExactSchemaFieldSetAndNoCredentialMaterial() {
        ViewOnlyCheckpointCommand command = new ViewOnlyCheckpointCommand(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174002"), LEASE_ID,
                D1, D2, D3, 0, null, ViewOnlyCheckpointState.DECISION_AUTHORIZED,
                "decision-authorized", D4, D5, null, D6, D1, D2, D3, false, NOW);
        ViewOnlyCheckpointSigningInput input = new ViewOnlyCheckpointSigningInput(
                UUID.fromString("123e4567-e89b-42d3-a456-426614174003"), command,
                binding, D1, D2, D3, executorCaller(), NOW.plusSeconds(900));
        ObjectNode payload = factory.checkpoint(input);

        assertThat(fieldNames(payload)).isEqualTo(Set.of(
                "schemaVersion", "receiptId", "leaseId", "leaseEnvelopeSha256",
                "transactionIdSha256", "bindingSha256", "binding",
                "evaluationPreflightReceiptEnvelopeSha256", "redemptionPreflightReceiptEnvelopeSha256",
                "authorizationEnvelopeSha256", "sequence", "previousState", "state", "reasonCode",
                "storedObjectSha256", "previousStoredObjectSha256", "localCheckpointSha256",
                "localPayloadSha256", "idempotencyKeySha256", "executorCaller", "createdAt",
                "expiresAt", "terminal", "credentialMaterialStored"));
        assertThat(payload.get("credentialMaterialStored").booleanValue()).isFalse();
        assertThat(payload.get("previousState").isNull()).isTrue();
    }

    private VerifiedViewOnlyPreflightReceipt receipt(String envelope, Instant issuedAt) {
        return new VerifiedViewOnlyPreflightReceipt(
                envelope, D4, D5, issuedAt, issuedAt.plusSeconds(300), 0, false);
    }

    private ViewOnlyOidcCaller executorCaller() {
        return new ViewOnlyOidcCaller(
                "executor", caller.issuer(),
                "repo:Halildeu/platform-k8s-gitops:ref:" + caller.ref(),
                caller.actorId(), caller.repositoryId(), caller.runId(), 1,
                caller.ref(), caller.headSha(), D2);
    }

    private static Set<String> fieldNames(ObjectNode node) {
        java.util.HashSet<String> names = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return Set.copyOf(names);
    }
}
