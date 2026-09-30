package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

/** One-use authority redemption with response-loss recovery and fresh zero-mutation preflight. */
public final class ViewOnlyLeaseRedemptionService {
    private static final String DSSE_ENVELOPE_DOMAIN = "faz22.6/view-only/dsse-envelope/v1";

    private final JdbcViewOnlyCheckpointCas cas;
    private final ViewOnlyLivePreflightRevalidator revalidator;
    private final ViewOnlyLeaseReceiptSigner signer;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final Clock clock;

    public ViewOnlyLeaseRedemptionService(JdbcViewOnlyCheckpointCas cas,
                                          ViewOnlyLivePreflightRevalidator revalidator,
                                          ViewOnlyLeaseReceiptSigner signer,
                                          RemoteViewJsonCanonicalizer canonicalizer,
                                          Clock clock) {
        this.cas = cas;
        this.revalidator = revalidator;
        this.signer = signer;
        this.canonicalizer = canonicalizer;
        this.clock = clock;
    }

    public byte[] redeem(VerifiedViewOnlyLeaseRedeem verified) {
        ViewOnlyLeaseRedeemCommand command = verified.command();
        Optional<byte[]> retry = cas.findLeaseRetry(
                command.requestId(), command.idempotencyKeySha256(), command.requestBodySha256(),
                command.callerIdentitySha256(), command.authorization().envelopeSha256(),
                command.transactionIdSha256());
        if (retry.isPresent()) {
            return retry.get();
        }

        VerifiedViewOnlyPreflightReceipt refreshed = revalidator.revalidate(command.binding(), verified.caller());
        requireRefreshedReceipt(command, refreshed);
        Instant issuedAt = clock.instant();
        Instant requestedExpiry = issuedAt.plusSeconds(command.requestedTtlSeconds());
        Instant expiresAt = requestedExpiry.isBefore(command.authorization().expiresAt())
                ? requestedExpiry : command.authorization().expiresAt();
        if (expiresAt.isBefore(issuedAt.plusSeconds(900))) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.CONTRACT_INVALID,
                    "authorization lifetime cannot satisfy the minimum checkpoint lease TTL");
        }

        UUID leaseId = UUID.randomUUID();
        byte[] signed;
        try {
            signed = signer.sign(new ViewOnlyLeaseSigningInput(
                    leaseId, command, verified.caller(), refreshed, issuedAt, expiresAt));
        } catch (RuntimeException signingFailure) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.SIGNING_UNAVAILABLE,
                    "checkpoint lease signer failed closed", signingFailure);
        }
        if (signed == null || signed.length == 0 || signed.length > 1_048_576) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.SIGNING_UNAVAILABLE,
                    "signed checkpoint lease is empty or exceeds 1 MiB");
        }
        JsonNode signedEnvelope = canonicalizer.strictParse(strictUtf8(signed));
        String leaseEnvelopeSha256 = new ViewOnlyDigest(canonicalizer)
                .domainDigest(DSSE_ENVELOPE_DOMAIN, "envelope", signedEnvelope);
        return cas.registerLease(new ViewOnlyLeaseRecord(
                leaseId, command.requestId(), command.idempotencyKeySha256(), command.requestBodySha256(),
                command.callerIdentitySha256(), command.transactionIdSha256(), command.bindingSha256(),
                command.binding(), leaseEnvelopeSha256, command.evaluationPreflight().envelopeSha256(),
                refreshed.envelopeSha256(), command.authorization().envelopeSha256(), signed,
                issuedAt, expiresAt, command.requestedMaxWrites()));
    }

    public Optional<byte[]> recoverCommitted(ViewOnlyLeaseRetryCandidate retry) {
        return cas.findLeaseRetry(
                retry.requestId(), retry.idempotencyKeySha256(), retry.requestBodySha256(),
                retry.callerIdentitySha256(), retry.authorizationEnvelopeSha256(),
                retry.transactionIdSha256());
    }

    private void requireRefreshedReceipt(ViewOnlyLeaseRedeemCommand command,
                                         VerifiedViewOnlyPreflightReceipt refreshed) {
        Instant now = clock.instant();
        if (!command.bindingSha256().equals(refreshed.bindingSha256())
                || !command.transactionIdSha256().equals(refreshed.transactionIdSha256())
                || refreshed.issuedAt().isAfter(now)
                || !now.isBefore(refreshed.expiresAt())
                || now.minusSeconds(300).isAfter(refreshed.issuedAt())) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.LEASE_BINDING_MISMATCH,
                    "redemption-time preflight is stale or does not match the transaction");
        }
    }

    private static String strictUtf8(byte[] value) {
        try {
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(value)).toString();
        } catch (java.nio.charset.CharacterCodingException invalid) {
            throw new ViewOnlyAuthorityException(
                    ViewOnlyAuthorityError.SIGNING_UNAVAILABLE, "signed lease is not strict UTF-8 JSON");
        }
    }
}
