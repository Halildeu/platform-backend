package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;

/** Concrete checkpoint-signer trust-root and exact lease payload verifier. */
public final class StrictViewOnlyLeaseEnvelopeVerifier implements ViewOnlyLeaseEnvelopeVerifier {
    private static final String PAYLOAD_TYPE =
            "application/vnd.acik.faz22-6-view-only-checkpoint-lease.v1+json";
    private static final String BINDING_DOMAIN = "faz22.6/view-only/transaction-binding/v1";
    private static final String TRANSACTION_DOMAIN = "faz22.6/view-only/transaction-id/v1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schemaVersion", "leaseId", "redeemRequestId", "idempotencyKeySha256",
            "transactionIdSha256", "bindingSha256", "binding",
            "evaluationPreflightReceiptEnvelopeSha256", "redemptionPreflightReceiptEnvelopeSha256",
            "redemptionPreflightIssuedAt", "authorizationEnvelopeSha256", "authorizationPayloadType",
            "authorizationRedemptionCount", "authorizationCaller", "executorProfile", "issuedAt",
            "expiresAt", "sequenceMinimumInclusive", "sequenceMaximumInclusive", "maxWrites", "closed");

    private final ViewOnlyPublicTrustStore trust;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;

    public StrictViewOnlyLeaseEnvelopeVerifier(ViewOnlyPublicTrustStore trust,
                                               RemoteViewJsonCanonicalizer canonicalizer) {
        this.trust = trust;
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
    }

    @Override
    public VerifiedViewOnlyLeaseEnvelope verify(JsonNode envelope, Instant now) {
        JsonNode untrusted = untrustedPayload(envelope);
        Instant issuedAt = instant(untrusted, "issuedAt");
        ViewOnlyPublicTrustStore.VerifiedDsse verified = trust.verifyRuntime(
                envelope, PAYLOAD_TYPE, "checkpoint-signer", issuedAt);
        JsonNode payload = verified.payload();
        exactFields(payload, EXACT_FIELDS, "checkpoint lease");
        requireText(payload, "schemaVersion", "faz22.6.viewOnlyCheckpointLease.v1");
        JsonNode binding = ViewOnlyTransactionBinding.requireExact(payload.get("binding"));
        String bindingDigest = digest.domainDigest(BINDING_DOMAIN, "binding", binding);
        String transactionDigest = digest.domainDigest(TRANSACTION_DOMAIN, "binding", binding);
        requireText(payload, "bindingSha256", bindingDigest);
        requireText(payload, "transactionIdSha256", transactionDigest);
        requireText(payload, "authorizationPayloadType", VerifiedViewOnlyAuthorization.PAYLOAD_TYPE);
        requireInteger(payload, "authorizationRedemptionCount", 1);
        requireInteger(payload, "sequenceMinimumInclusive", 0);
        requireInteger(payload, "sequenceMaximumInclusive", 63);
        requireInteger(payload, "maxWrites", 64);
        requireBoolean(payload, "closed", false);
        Instant expiresAt = instant(payload, "expiresAt");
        if (issuedAt.isAfter(now) || !now.isBefore(expiresAt) || !issuedAt.isBefore(expiresAt)) {
            throw invalid("checkpoint lease is stale or has an invalid lifetime");
        }
        JsonNode caller = payload.get("authorizationCaller");
        exactFields(caller, Set.of("profile", "subject", "runId", "runAttempt", "headSha", "tokenJtiSha256"),
                "lease authorization caller");
        requireText(caller, "profile", "authorization");
        requireText(caller, "subject", "repo:Halildeu/platform-k8s-gitops:environment:faz22-view-only-pilot");
        requireLong(caller, "runId", binding.get("runId").longValue());
        requireInteger(caller, "runAttempt", 1);
        requireText(caller, "headSha", text(binding, "headSha"));
        ViewOnlyDigest.requireSha256(text(caller, "tokenJtiSha256"), "authorizationCaller.tokenJtiSha256");
        JsonNode executor = payload.get("executorProfile");
        exactFields(executor, Set.of("audience", "subject", "runnerEnvironment"), "lease executor profile");
        requireText(executor, "audience", ViewOnlyGithubOidcProfile.EXECUTOR.audience());
        requireText(executor, "subject", "repo:Halildeu/platform-k8s-gitops:ref:" + text(binding, "intentRef"));
        requireText(executor, "runnerEnvironment", "self-hosted");
        return new VerifiedViewOnlyLeaseEnvelope(
                uuid(payload, "leaseId"), verified.envelopeSha256(), transactionDigest, bindingDigest,
                binding, ViewOnlyOidcBinding.fromJson(binding),
                digestText(payload, "evaluationPreflightReceiptEnvelopeSha256"),
                digestText(payload, "redemptionPreflightReceiptEnvelopeSha256"),
                digestText(payload, "authorizationEnvelopeSha256"), expiresAt, false, 0, 63, 64);
    }

    private JsonNode untrustedPayload(JsonNode envelope) {
        try {
            return canonicalizer.strictParse(new String(
                    java.util.Base64.getDecoder().decode(text(envelope, "payload")),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalidPayload) {
            throw invalid("checkpoint lease payload is not strict base64 JSON", invalidPayload);
        }
    }

    private static void exactFields(JsonNode object, Set<String> expected, String label) {
        if (object == null || !object.isObject()) {
            throw invalid(label + " must be an object");
        }
        Set<String> actual = new HashSet<>();
        Iterator<String> names = object.fieldNames();
        names.forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw invalid(label + " fields do not match the exact schema");
        }
    }

    private static String text(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
    }

    private static String digestText(JsonNode object, String field) {
        return ViewOnlyDigest.requireSha256(text(object, field), field);
    }

    private static void requireText(JsonNode object, String field, String expected) {
        if (!expected.equals(text(object, field))) {
            throw invalid(field + " does not match verified authority");
        }
    }

    private static Instant instant(JsonNode object, String field) {
        try {
            return Instant.parse(text(object, field));
        } catch (Exception invalidTime) {
            throw invalid(field + " is not a canonical instant", invalidTime);
        }
    }

    private static UUID uuid(JsonNode object, String field) {
        try {
            return UUID.fromString(text(object, field));
        } catch (Exception invalidUuid) {
            throw invalid(field + " is not a UUID", invalidUuid);
        }
    }

    private static void requireInteger(JsonNode object, String field, int expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() != expected) {
            throw invalid(field + " does not match its exact integer value");
        }
    }

    private static void requireLong(JsonNode object, String field, long expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() != expected) {
            throw invalid(field + " does not match its exact integer value");
        }
    }

    private static void requireBoolean(JsonNode object, String field, boolean expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw invalid(field + " does not match its exact boolean value");
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return invalid(message, null);
    }

    private static ViewOnlyAuthorityException invalid(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message, cause);
    }
}
