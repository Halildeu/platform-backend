package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Concrete runtime-attestor DSSE, schema, freshness and zero-mutation verifier. */
public final class StrictViewOnlyPreflightEnvelopeVerifier implements ViewOnlyPreflightEnvelopeVerifier {
    public static final String PAYLOAD_TYPE =
            "application/vnd.acik.faz22-6-view-only-live-preflight-attestation.v1+json";
    private static final String BINDING_DOMAIN = "faz22.6/view-only/transaction-binding/v1";
    private static final String TRANSACTION_DOMAIN = "faz22.6/view-only/transaction-id/v1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schemaVersion", "receiptId", "requestId", "idempotencyKeySha256", "requestSha256",
            "bindingHandoffEnvelopeSha256", "bindingSha256", "transactionIdSha256", "binding",
            "caller", "persona", "checks", "mutationCount", "attendedConsentAttempted", "issuedAt",
            "expiresAt", "maxUses", "replayIdentitySha256", "verdict");
    private static final Set<String> CHECK_NAMES = Set.of(
            "targetIdentity", "pkceAuthorizationCode", "tokenRefresh", "routeApi", "browserConsole",
            "replayIsolation", "clusterContext", "portsTunnels", "imageDigests", "policyMask",
            "runnerCapacity", "watchdogRollback");

    private final ViewOnlyPublicTrustStore trust;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;

    public StrictViewOnlyPreflightEnvelopeVerifier(ViewOnlyPublicTrustStore trust,
                                                   RemoteViewJsonCanonicalizer canonicalizer) {
        this.trust = trust;
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
    }

    @Override
    public VerifiedViewOnlyPreflightReceipt verifyEvaluation(JsonNode envelope, Instant now) {
        JsonNode untrustedPayload = decodePayloadForIssuedAt(envelope);
        Instant issuedAt = instant(untrustedPayload, "issuedAt");
        ViewOnlyPublicTrustStore.VerifiedDsse verified = trust.verifyRuntime(
                envelope, PAYLOAD_TYPE, "runtime-attestor", issuedAt);
        JsonNode payload = verified.payload();
        exactFields(payload, EXACT_FIELDS, "preflight receipt");
        requireText(payload, "schemaVersion", "faz22.6.viewOnlyLivePreflightAttestation.v1");
        ViewOnlyDigest.requireSha256(text(payload, "idempotencyKeySha256"), "idempotencyKeySha256");
        ViewOnlyDigest.requireSha256(text(payload, "requestSha256"), "requestSha256");
        ViewOnlyDigest.requireSha256(text(payload, "bindingHandoffEnvelopeSha256"),
                "bindingHandoffEnvelopeSha256");
        ViewOnlyDigest.requireSha256(text(payload, "replayIdentitySha256"), "replayIdentitySha256");
        JsonNode binding = ViewOnlyTransactionBinding.requireExact(payload.get("binding"));
        String bindingSha256 = digest.domainDigest(BINDING_DOMAIN, "binding", binding);
        String transactionIdSha256 = digest.domainDigest(TRANSACTION_DOMAIN, "binding", binding);
        requireText(payload, "bindingSha256", bindingSha256);
        requireText(payload, "transactionIdSha256", transactionIdSha256);

        Instant expiresAt = instant(payload, "expiresAt");
        if (issuedAt.isAfter(now) || !now.isBefore(expiresAt) || !issuedAt.isBefore(expiresAt)
                || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofSeconds(300)) > 0) {
            throw invalid("preflight receipt lifetime is stale or outside 300 seconds");
        }
        requireInteger(payload, "mutationCount", 0);
        requireBoolean(payload, "attendedConsentAttempted", false);
        requireInteger(payload, "maxUses", 1);
        requireText(payload, "verdict", "PASS");
        requireCaller(payload.get("caller"), binding);
        requirePersona(payload.get("persona"), binding, issuedAt);
        requireChecks(payload.get("checks"), issuedAt, expiresAt);
        return new VerifiedViewOnlyPreflightReceipt(
                verified.envelopeSha256(), bindingSha256, transactionIdSha256,
                issuedAt, expiresAt, 0, false);
    }

    private JsonNode decodePayloadForIssuedAt(JsonNode envelope) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(text(envelope, "payload"));
            if (bytes.length == 0 || bytes.length > 524_288) {
                throw invalid("preflight payload is outside its hard bound");
            }
            return canonicalizer.strictParse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException invalidBase64) {
            throw invalid("preflight payload is not strict base64", invalidBase64);
        }
    }

    private static void requireCaller(JsonNode caller, JsonNode binding) {
        exactFields(caller, Set.of(
                "profile", "issuer", "audience", "subject", "repository", "repositoryId",
                "workflowRef", "ref", "headSha", "runId", "runAttempt", "runnerEnvironment",
                "tokenIssuedAt", "tokenExpiresAt", "tokenJtiSha256"), "preflight caller");
        requireText(caller, "profile", "preflight");
        requireText(caller, "issuer", "https://token.actions.githubusercontent.com");
        requireText(caller, "audience", ViewOnlyGithubOidcProfile.PREFLIGHT.audience());
        requireText(caller, "repository", "Halildeu/platform-k8s-gitops");
        requireText(caller, "repositoryId", "1211415632");
        requireText(caller, "ref", text(binding, "intentRef"));
        requireText(caller, "headSha", text(binding, "headSha"));
        requireText(caller, "workflowRef", text(binding, "workflowRef"));
        requireText(caller, "runnerEnvironment", "github-hosted");
        requireLong(caller, "runId", binding.get("runId").longValue());
        requireInteger(caller, "runAttempt", 1);
        requireText(caller, "subject", "repo:Halildeu/platform-k8s-gitops:ref:" + text(binding, "intentRef"));
        ViewOnlyDigest.requireSha256(text(caller, "tokenJtiSha256"), "caller.tokenJtiSha256");
        Instant tokenIssued = instant(caller, "tokenIssuedAt");
        Instant tokenExpires = instant(caller, "tokenExpiresAt");
        if (!tokenIssued.isBefore(tokenExpires)
                || Duration.between(tokenIssued, tokenExpires).compareTo(Duration.ofSeconds(300)) > 0) {
            throw invalid("preflight caller token lifetime is invalid");
        }
    }

    private static void requirePersona(JsonNode persona, JsonNode binding, Instant issuedAt) {
        exactFields(persona, Set.of(
                "identitySha256", "tenantIdSha256", "expiresAt", "preprovisioned",
                "adminCredentialUsed", "userConfigurationMutationCount"), "preflight persona");
        requireText(persona, "identitySha256", text(binding, "preflightPersonaIdentitySha256"));
        requireText(persona, "tenantIdSha256", text(binding, "tenantIdSha256"));
        requireBoolean(persona, "preprovisioned", true);
        requireBoolean(persona, "adminCredentialUsed", false);
        requireInteger(persona, "userConfigurationMutationCount", 0);
        if (instant(persona, "expiresAt").isBefore(issuedAt.plusSeconds(900))) {
            throw invalid("preflight persona does not cover the minimum activation window");
        }
    }

    private static void requireChecks(JsonNode checks, Instant receiptIssuedAt, Instant receiptExpiresAt) {
        exactFields(checks, CHECK_NAMES, "preflight checks");
        for (String name : CHECK_NAMES) {
            JsonNode check = checks.get(name);
            exactFields(check, Set.of(
                    "checkVersion", "status", "source", "evidenceSha256", "observedAt", "expiresAt"),
                    "preflight check " + name);
            if (!text(check, "checkVersion").matches("v[1-9][0-9]*")) {
                throw invalid("preflight check version is invalid");
            }
            requireText(check, "status", "PASS");
            if (!Set.of("attestor-runtime", "browser-fixed-function", "github-api", "kubernetes-readonly",
                    "remote-bridge-device-channel", "policy-bundle").contains(text(check, "source"))) {
                throw invalid("preflight check source is not fixed-function");
            }
            ViewOnlyDigest.requireSha256(text(check, "evidenceSha256"), "check.evidenceSha256");
            Instant observed = instant(check, "observedAt");
            Instant expires = instant(check, "expiresAt");
            if (observed.isAfter(receiptIssuedAt) || expires.isBefore(receiptExpiresAt)) {
                throw invalid("preflight check evidence is stale for the receipt lifetime");
            }
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
