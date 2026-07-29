package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

/** Coordinator-signed transaction binding handoff verifier for preflight admission. */
public final class StrictViewOnlyBindingHandoffVerifier {
    public static final String PAYLOAD_TYPE =
            "application/vnd.acik.faz22-6-view-only-transaction-binding-handoff.v1+json";
    private static final String BINDING_DOMAIN = "faz22.6/view-only/transaction-binding/v1";
    private static final String TRANSACTION_DOMAIN = "faz22.6/view-only/transaction-id/v1";
    private static final Set<String> EXACT_FIELDS = Set.of(
            "schemaVersion", "handoffId", "lookupRequestId", "idempotencyKeySha256", "binding",
            "bindingSha256", "transactionIdSha256", "derivation", "caller", "issuedAt", "expiresAt",
            "maxUses", "mutationCount");

    private final ViewOnlyPublicTrustStore trust;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;

    public StrictViewOnlyBindingHandoffVerifier(ViewOnlyPublicTrustStore trust,
                                                RemoteViewJsonCanonicalizer canonicalizer) {
        this.trust = trust;
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
    }

    public VerifiedBindingHandoff verify(JsonNode envelope, Instant now) {
        JsonNode untrusted = untrustedPayload(envelope);
        Instant issuedAt = instant(untrusted, "issuedAt");
        ViewOnlyPublicTrustStore.VerifiedDsse verified = trust.verifyCrossAi(
                envelope, PAYLOAD_TYPE, "coordinator", issuedAt);
        JsonNode payload = verified.payload();
        exactFields(payload, EXACT_FIELDS, "binding handoff");
        requireText(payload, "schemaVersion", "faz22.6.viewOnlyTransactionBindingHandoff.v1");
        JsonNode binding = ViewOnlyTransactionBinding.requireExact(payload.get("binding"));
        String bindingSha256 = digest.domainDigest(BINDING_DOMAIN, "binding", binding);
        String transactionIdSha256 = digest.domainDigest(TRANSACTION_DOMAIN, "binding", binding);
        requireText(payload, "bindingSha256", bindingSha256);
        requireText(payload, "transactionIdSha256", transactionIdSha256);
        requireInteger(payload, "maxUses", 1);
        requireInteger(payload, "mutationCount", 0);
        Instant expiresAt = instant(payload, "expiresAt");
        if (issuedAt.isAfter(now) || !now.isBefore(expiresAt) || !issuedAt.isBefore(expiresAt)
                || Duration.between(issuedAt, expiresAt).compareTo(Duration.ofSeconds(300)) > 0) {
            throw invalid("binding handoff is stale or outside the 300-second lifetime");
        }
        requireDerivation(payload.get("derivation"), binding);
        requireCaller(payload.get("caller"), binding);
        return new VerifiedBindingHandoff(
                binding, bindingSha256, transactionIdSha256, verified.envelopeSha256(), issuedAt, expiresAt);
    }

    private static void requireDerivation(JsonNode derivation, JsonNode binding) {
        exactFields(derivation, Set.of(
                "bundleSchemaVersion", "bundleRequestId", "bundleEnvelopeSha256", "registryState",
                "intentRefObjectId", "intentRefHeadSha", "intentRefFinalized", "dispatchAccepted",
                "dispatchWatermarkRunId", "dispatchRunId", "dispatchRunAttempt",
                "dispatchTriggeringActorId", "dispatchHeadBranch", "dispatchHeadRepository",
                "dispatchRepository", "dispatchWorkflowPath", "dispatchStatus", "dispatchHeadSha",
                "dispatchWorkflowRef", "correlatedAt"), "binding derivation");
        requireText(derivation, "bundleSchemaVersion", "acik.cross-ai-deployment-bundle.v3");
        requireText(derivation, "bundleEnvelopeSha256", text(binding, "intentBundleSha256"));
        requireText(derivation, "registryState", "DispatchAccepted");
        requireText(derivation, "intentRefObjectId", text(binding, "headSha"));
        requireText(derivation, "intentRefHeadSha", text(binding, "headSha"));
        requireBoolean(derivation, "intentRefFinalized", true);
        requireBoolean(derivation, "dispatchAccepted", true);
        requireLong(derivation, "dispatchRunId", binding.get("runId").longValue());
        requireInteger(derivation, "dispatchRunAttempt", 1);
        requireLong(derivation, "dispatchTriggeringActorId", binding.get("triggeringActorId").longValue());
        requireText(derivation, "dispatchHeadBranch", text(binding, "intentRef").substring("refs/tags/".length()));
        requireText(derivation, "dispatchHeadRepository", "Halildeu/platform-k8s-gitops");
        requireText(derivation, "dispatchRepository", "Halildeu/platform-k8s-gitops");
        requireText(derivation, "dispatchWorkflowPath", ViewOnlyGithubOidcValidator.WORKFLOW_PATH);
        if (!Set.of("queued", "in_progress").contains(text(derivation, "dispatchStatus"))) {
            throw invalid("binding dispatch status is not pre-execution");
        }
        requireText(derivation, "dispatchHeadSha", text(binding, "headSha"));
        requireText(derivation, "dispatchWorkflowRef", text(binding, "workflowRef"));
        instant(derivation, "correlatedAt");
    }

    private static void requireCaller(JsonNode caller, JsonNode binding) {
        exactFields(caller, Set.of(
                "profile", "subject", "ref", "workflowRef", "headSha", "runId", "runAttempt",
                "triggeringActorId", "runnerEnvironment", "tokenJtiSha256"), "binding caller");
        requireText(caller, "profile", "binding");
        requireText(caller, "subject", "repo:Halildeu/platform-k8s-gitops:ref:" + text(binding, "intentRef"));
        requireText(caller, "ref", text(binding, "intentRef"));
        requireText(caller, "workflowRef", text(binding, "workflowRef"));
        requireText(caller, "headSha", text(binding, "headSha"));
        requireLong(caller, "runId", binding.get("runId").longValue());
        requireInteger(caller, "runAttempt", 1);
        requireLong(caller, "triggeringActorId", binding.get("triggeringActorId").longValue());
        requireText(caller, "runnerEnvironment", "github-hosted");
        ViewOnlyDigest.requireSha256(text(caller, "tokenJtiSha256"), "bindingCaller.tokenJtiSha256");
    }

    private JsonNode untrustedPayload(JsonNode envelope) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(text(envelope, "payload"));
            if (bytes.length == 0 || bytes.length > 65_536) {
                throw invalid("binding handoff payload is outside its hard bound");
            }
            return canonicalizer.strictParse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalidPayload) {
            throw invalid("binding handoff payload is not strict base64 JSON", invalidPayload);
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
            throw invalid(label + " fields do not match the exact contract");
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
            throw invalid(field + " does not match verified binding authority");
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

    public record VerifiedBindingHandoff(
            JsonNode binding,
            String bindingSha256,
            String transactionIdSha256,
            String envelopeSha256,
            Instant issuedAt,
            Instant expiresAt) {
        public VerifiedBindingHandoff {
            binding = binding.deepCopy();
        }

        @Override
        public JsonNode binding() {
            return binding.deepCopy();
        }
    }
}
