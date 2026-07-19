package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact v3 transaction binding shared by every authority surface. */
public final class ViewOnlyTransactionBinding {
    private static final Pattern DIGEST = Pattern.compile("sha256:[a-f0-9]{64}");
    private static final Pattern GIT_SHA = Pattern.compile("[a-f0-9]{40}");
    private static final Set<String> EXACT_FIELDS = Set.of(
            "repositoryId", "repository", "environment", "deploymentClass", "productSlice",
            "intentRef", "intentBundleSha256", "transactionSessionSha256", "headSha",
            "workflowPath", "workflowRef", "workflowBlobSha256", "dependencyLockSha256",
            "concurrencySha256", "authoritySetSha256", "runId", "runAttempt", "triggeringActorId",
            "machineAuthorityPolicySha256", "artifactSetSha256", "rollbackPlanSha256",
            "postDeployVerifierSha256", "bootstrapCredentialSha256", "tenantIdSha256",
            "preflightPersonaIdentitySha256", "endpointIdSha256", "operatorIdSha256",
            "deviceHostnameSha256", "attendedConsentPolicySha256", "pilotOwnerPolicySha256",
            "maskPolicySha256", "runtimeImageDigest", "pilotSeconds", "transactionScopeSha256",
            "runnerPolicySha256", "runnerAdmissionLeaseSha256");
    private static final Set<String> DIGEST_FIELDS = Set.of(
            "intentBundleSha256", "transactionSessionSha256", "workflowBlobSha256",
            "dependencyLockSha256", "concurrencySha256", "authoritySetSha256",
            "machineAuthorityPolicySha256", "artifactSetSha256", "rollbackPlanSha256",
            "postDeployVerifierSha256", "bootstrapCredentialSha256", "tenantIdSha256",
            "preflightPersonaIdentitySha256", "endpointIdSha256", "operatorIdSha256",
            "deviceHostnameSha256", "attendedConsentPolicySha256", "pilotOwnerPolicySha256",
            "maskPolicySha256", "runtimeImageDigest", "transactionScopeSha256",
            "runnerPolicySha256", "runnerAdmissionLeaseSha256");

    private ViewOnlyTransactionBinding() {
    }

    public static JsonNode requireExact(JsonNode binding) {
        if (binding == null || !binding.isObject()) {
            throw invalid("transaction binding must be an object");
        }
        Set<String> fields = new HashSet<>();
        Iterator<String> names = binding.fieldNames();
        names.forEachRemaining(fields::add);
        if (!fields.equals(EXACT_FIELDS)) {
            throw invalid("transaction binding fields do not match the exact v3 contract");
        }
        requireLong(binding, "repositoryId", 1, 9_007_199_254_740_991L);
        requireLong(binding, "runId", 1, 9_007_199_254_740_991L);
        requireLong(binding, "triggeringActorId", 1, 9_007_199_254_740_991L);
        requireInteger(binding, "runAttempt", 1, 1);
        requireInteger(binding, "pilotSeconds", 300, 1800);
        requireText(binding, "repository", "Halildeu/platform-k8s-gitops");
        requireText(binding, "environment", "faz22-view-only-pilot");
        requireText(binding, "deploymentClass", "reversible-test");
        requireText(binding, "productSlice", "Halildeu/platform-k8s-gitops#2373");
        requireText(binding, "workflowPath", ViewOnlyGithubOidcValidator.WORKFLOW_PATH);
        String intentRef = text(binding, "intentRef");
        if (!intentRef.matches("refs/tags/cross-ai-intent/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                + "[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
            throw invalid("transaction intentRef is invalid");
        }
        String headSha = text(binding, "headSha");
        if (!GIT_SHA.matcher(headSha).matches()) {
            throw invalid("transaction headSha is invalid");
        }
        requireText(binding, "workflowRef",
                "Halildeu/platform-k8s-gitops/" + ViewOnlyGithubOidcValidator.WORKFLOW_PATH + "@" + intentRef);
        DIGEST_FIELDS.forEach(field -> {
            if (!DIGEST.matcher(text(binding, field)).matches()) {
                throw invalid(field + " is not a lowercase SHA-256 digest");
            }
        });
        if (!text(binding, "authoritySetSha256").equals(text(binding, "transactionScopeSha256"))) {
            throw invalid("authority set and transaction scope digests must be identical");
        }
        int pilotSeconds = binding.get("pilotSeconds").intValue();
        if (!Set.of(300, 600, 900, 1200, 1800).contains(pilotSeconds)) {
            throw invalid("pilotSeconds is outside its exact enum");
        }
        return binding.deepCopy();
    }

    static String text(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isTextual() || value.textValue().isBlank()) {
            throw invalid(field + " must be non-blank text");
        }
        return value.textValue();
    }

    static long longValue(JsonNode object, String field) {
        requireLong(object, field, 1, 9_007_199_254_740_991L);
        return object.get(field).longValue();
    }

    private static void requireText(JsonNode object, String field, String expected) {
        if (!expected.equals(text(object, field))) {
            throw invalid(field + " does not match the pinned transaction authority");
        }
    }

    private static void requireLong(JsonNode object, String field, long minimum, long maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()
                || value.longValue() < minimum || value.longValue() > maximum) {
            throw invalid(field + " is outside its exact integer bound");
        }
    }

    private static void requireInteger(JsonNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(field + " is outside its exact integer bound");
        }
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message);
    }
}
