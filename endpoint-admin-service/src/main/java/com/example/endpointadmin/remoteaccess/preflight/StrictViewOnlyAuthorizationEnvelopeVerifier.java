package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Concrete Codex-only Cross-AI v3 authorization verifier.
 *
 * <p>It verifies coordinator, runner-management and provider-review DSSE,
 * signed revocations, one-transaction authority, review chain/closure and the
 * complete mapping from the v3 bundle into the coordinator-signed binding.
 * The legacy v1/v2 three-stage contract is deliberately not accepted.</p>
 */
public final class StrictViewOnlyAuthorizationEnvelopeVerifier
        implements ViewOnlyAuthorizationEnvelopeVerifier {
    private static final String BUNDLE_PAYLOAD_TYPE = VerifiedViewOnlyAuthorization.PAYLOAD_TYPE;
    private static final String REVIEW_PAYLOAD_TYPE =
            "application/vnd.acik.cross-ai-deployment-review.v2+json";
    private static final String RUNNER_LEASE_PAYLOAD_TYPE =
            "application/vnd.acik.cross-ai-runner-admission-lease.v1+json";
    private static final String BINDING_DOMAIN = "faz22.6/view-only/transaction-binding/v1";
    private static final String TRANSACTION_DOMAIN = "faz22.6/view-only/transaction-id/v1";
    private static final String DSSE_DOMAIN = "faz22.6/view-only/dsse-envelope/v1";
    private static final String SESSION_DOMAIN = "acik.cross-ai-deployment-session.v3";
    private static final String CLOSURE_DOMAIN = "acik.cross-ai-deployment-closure.v3";
    private static final String AUTHORITY_SET_DOMAIN = "acik.cross-ai-transaction-authority-set.v1";
    private static final Set<String> BUNDLE_FIELDS = Set.of(
            "schemaVersion", "bundleId", "subject", "workflowStages", "runnerAdmissionLeaseEnvelope",
            "reviewEnvelopes", "closure", "consensus", "grant");
    private static final Set<String> SUBJECT_FIELDS = Set.of(
            "repositoryId", "repository", "headSha", "intentRef", "environment", "deploymentClass",
            "productSlice", "policySha256", "artifactSetSha256", "rollbackPlanSha256",
            "postDeployVerifierSha256", "runnerPolicySha256", "runnerAdmissionLeaseSha256",
            "bootstrapCredentialSha256", "sessionSha256", "endpointIdSha256", "deviceHostnameSha256",
            "operatorIdSha256", "attendedConsentPolicySha256", "pilotOwnerPolicySha256",
            "maskPolicySha256", "runtimeImageDigest", "pilotSeconds", "transactionScopeSha256");
    private static final Set<String> STAGE_FIELDS = Set.of(
            "stage", "order", "workflowPath", "workflowBlobSha256", "dependencyLockSha256",
            "concurrencyGroupSha256", "authorityFiles", "preflightRunsOnLabels", "runsOnLabels",
            "maxUses", "requiresSameRunPreflight", "requiresOneProtectedEnvironmentGate");
    private static final Set<String> OPTIONAL_STAGE_FIELDS = Set.of(
            "runnerGroupId", "runnerAttestationClass");
    private static final Set<String> GRANT_FIELDS = Set.of(
            "requestId", "deploymentSessionId", "transactionNonceSha256", "triggeringActorId",
            "triggeringActorLogin", "registrationPrincipal", "workflowEvent", "notBefore", "expiresAt",
            "sequence", "failureTransition", "authorizationMode", "maxRunAttempts");
    private static final Set<String> REVIEW_FIELDS = Set.of(
            "schemaVersion", "reviewId", "reviewChainId", "providerFamily", "channel",
            "directProviderCli", "modelId", "modelIdentityClass", "reasoningEffort", "sandbox",
            "ephemeral", "capabilitySnapshotSha256", "subjectSha256", "round", "verdict",
            "inputSha256", "outputSha256", "findingsSha256", "previousRoundSha256", "findingIds",
            "resolvedFindingIds", "acknowledgedFindingIds", "closureRootSha256", "issuedAt",
            "expiresAt", "issuer", "keyId");

    private final ViewOnlyPublicTrustStore trust;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyDigest digest;

    public StrictViewOnlyAuthorizationEnvelopeVerifier(ViewOnlyPublicTrustStore trust,
                                                       RemoteViewJsonCanonicalizer canonicalizer) {
        this.trust = trust;
        this.canonicalizer = canonicalizer;
        this.digest = new ViewOnlyDigest(canonicalizer);
    }

    @Override
    public VerifiedViewOnlyAuthorization verify(JsonNode envelope, JsonNode bindingValue, Instant now) {
        JsonNode binding = ViewOnlyTransactionBinding.requireExact(bindingValue);
        JsonNode untrusted = untrustedPayload(envelope);
        JsonNode untrustedGrant = object(untrusted, "grant");
        Instant grantStart = instant(untrustedGrant, "notBefore");
        ViewOnlyPublicTrustStore.VerifiedDsse outer = trust.verifyCrossAi(
                envelope, BUNDLE_PAYLOAD_TYPE, "coordinator", grantStart);
        JsonNode bundle = outer.payload();
        exactFields(bundle, BUNDLE_FIELDS, "v3 bundle");
        requireText(bundle, "schemaVersion", "acik.cross-ai-deployment-bundle.v3");
        JsonNode subject = object(bundle, "subject");
        JsonNode grant = object(bundle, "grant");
        exactFields(subject, SUBJECT_FIELDS, "v3 subject");
        exactFields(grant, GRANT_FIELDS, "v3 grant");
        Instant expiresAt = instant(grant, "expiresAt");
        if (!grantStart.isBefore(expiresAt)
                || Duration.between(grantStart, expiresAt).compareTo(Duration.ofMinutes(120)) > 0
                || grantStart.isAfter(now.plusSeconds(30)) || !now.minusSeconds(30).isBefore(expiresAt)) {
            throw invalid("v3 grant is not active inside the 120-minute bound");
        }
        String requestId = text(grant, "requestId");
        requireText(subject, "intentRef", "refs/tags/cross-ai-intent/" + requestId);
        requireText(grant, "workflowEvent", "workflow_dispatch");
        requireText(grant, "failureTransition", "transaction->compensating-rollback-in-run");
        requireText(grant, "authorizationMode", "dual-gate");
        requireInteger(grant, "maxRunAttempts", 1);
        requireSingleTextArray(grant.get("sequence"), "transaction", "grant.sequence");

        ArrayNode stages = array(bundle, "workflowStages", 1, 1);
        JsonNode stage = stages.get(0);
        exactStageFields(stage);
        requireText(stage, "stage", "transaction");
        requireInteger(stage, "order", 1);
        requireText(stage, "workflowPath", ViewOnlyGithubOidcValidator.WORKFLOW_PATH);
        requireInteger(stage, "maxUses", 1);
        requireBoolean(stage, "requiresSameRunPreflight", true);
        requireBoolean(stage, "requiresOneProtectedEnvironmentGate", true);
        requireNonEmptyStringArray(stage.get("preflightRunsOnLabels"), "preflightRunsOnLabels");
        requireNonEmptyStringArray(stage.get("runsOnLabels"), "runsOnLabels");
        if (stage.has("runnerGroupId")) {
            longValue(stage, "runnerGroupId");
        }
        if (stage.has("runnerAttestationClass")
                && !text(stage, "runnerAttestationClass").matches("^[a-z][a-z0-9_.-]{2,63}$")) {
            throw invalid("runnerAttestationClass is outside the exact v3 contract");
        }
        String authoritySetSha256 = authoritySetDigest(stage.get("authorityFiles"));
        requireText(subject, "transactionScopeSha256", authoritySetSha256);

        ObjectNode subjectProjection = canonicalizer.mapper().createObjectNode();
        subjectProjection.set("subject", subject);
        subjectProjection.set("workflowStages", stages);
        subjectProjection.set("grant", grant);
        String subjectSha256 = canonicalizer.digest(subjectProjection);
        requireSession(subject, grant);
        verifyRunnerLease(bundle, subject, grant, now);
        Map<String, Review> reviews = verifyReviews(bundle, subjectSha256, now);
        String closureRoot = verifyClosure(bundle, reviews, subjectSha256);
        verifyConsensus(bundle, reviews, subjectSha256, closureRoot);

        String plainBundleEnvelopeSha256 = canonicalizer.digest(envelope);
        if (trust.isCrossAiRevoked("bundle", text(bundle, "bundleId"), now)
                || trust.isCrossAiRevoked("subject", subjectSha256, now)
                || trust.isCrossAiRevoked("grant", requestId, now)) {
            throw invalid("v3 bundle, subject or grant is revoked");
        }
        requireBinding(binding, subject, stage, grant, authoritySetSha256, plainBundleEnvelopeSha256);
        String bindingSha256 = digest.domainDigest(BINDING_DOMAIN, "binding", binding);
        String transactionIdSha256 = digest.domainDigest(TRANSACTION_DOMAIN, "binding", binding);
        return new VerifiedViewOnlyAuthorization(
                digest.domainDigest(DSSE_DOMAIN, "envelope", envelope), BUNDLE_PAYLOAD_TYPE,
                bindingSha256, transactionIdSha256, grantStart, expiresAt, false);
    }

    private void requireSession(JsonNode subject, JsonNode grant) {
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", SESSION_DOMAIN);
        projection.put("requestId", text(grant, "requestId"));
        projection.put("deploymentSessionId", text(grant, "deploymentSessionId"));
        projection.set("repositoryId", subject.get("repositoryId"));
        projection.put("environment", text(subject, "environment"));
        projection.put("headSha", text(subject, "headSha"));
        projection.put("intentRef", text(subject, "intentRef"));
        projection.put("bootstrapCredentialSha256", digestText(subject, "bootstrapCredentialSha256"));
        projection.put("endpointIdSha256", digestText(subject, "endpointIdSha256"));
        projection.put("operatorIdSha256", digestText(subject, "operatorIdSha256"));
        projection.put("deviceHostnameSha256", digestText(subject, "deviceHostnameSha256"));
        projection.put("pilotOwnerPolicySha256", digestText(subject, "pilotOwnerPolicySha256"));
        projection.put("maskPolicySha256", digestText(subject, "maskPolicySha256"));
        projection.put("runtimeImageDigest", digestText(subject, "runtimeImageDigest"));
        projection.set("pilotSeconds", subject.get("pilotSeconds"));
        projection.put("transactionScopeSha256", digestText(subject, "transactionScopeSha256"));
        requireText(subject, "sessionSha256", canonicalizer.digest(projection));
    }

    private void verifyRunnerLease(JsonNode bundle, JsonNode subject, JsonNode grant, Instant now) {
        JsonNode envelope = object(bundle, "runnerAdmissionLeaseEnvelope");
        JsonNode untrusted = untrustedPayload(envelope);
        Instant issuedAt = instant(untrusted, "issuedAt");
        ViewOnlyPublicTrustStore.VerifiedDsse verified = trust.verifyCrossAi(
                envelope, RUNNER_LEASE_PAYLOAD_TYPE, "runner-management", issuedAt);
        JsonNode lease = verified.payload();
        exactFields(lease, Set.of(
                "schemaVersion", "leaseId", "requestId", "repositoryId", "repository", "environment",
                "headSha", "intentRef", "runnerPolicySha256", "inventoryGenerationSha256", "issuedAt",
                "expiresAt", "eligibleRunners"), "runner admission lease");
        requireText(lease, "schemaVersion", "acik.cross-ai-runner-admission-lease.v1");
        requireText(subject, "runnerAdmissionLeaseSha256", canonicalizer.digest(envelope));
        requireText(lease, "requestId", text(grant, "requestId"));
        requireSame(lease, subject, "repositoryId");
        for (String field : Set.of("repository", "environment", "headSha", "intentRef", "runnerPolicySha256")) {
            requireText(lease, field, text(subject, field));
        }
        Instant expiresAt = instant(lease, "expiresAt");
        if (issuedAt.isAfter(now.plusSeconds(30)) || !now.minusSeconds(30).isBefore(expiresAt)
                || issuedAt.isAfter(instant(grant, "notBefore").plusSeconds(30))
                || expiresAt.isBefore(instant(grant, "expiresAt"))) {
            throw invalid("runner admission lease does not cover the signed grant");
        }
        ArrayNode runners = array(lease, "eligibleRunners", 1, 100);
        Set<Long> runnerIds = new HashSet<>();
        Set<String> runnerNames = new HashSet<>();
        for (JsonNode runner : runners) {
            exactFields(runner, Set.of("runnerId", "runnerNameSha256", "labels", "attestationClass"),
                    "eligible runner");
            long runnerId = longValue(runner, "runnerId");
            String runnerName = digestText(runner, "runnerNameSha256");
            if (!runnerIds.add(runnerId) || !runnerNames.add(runnerName)) {
                throw invalid("runner admission lease contains duplicate runner identity");
            }
            requireNonEmptyStringArray(runner.get("labels"), "eligibleRunner.labels");
            text(runner, "attestationClass");
        }
        if (trust.isCrossAiRevoked("runner-lease", text(lease, "leaseId"), now)) {
            throw invalid("runner admission lease is revoked");
        }
    }

    private Map<String, Review> verifyReviews(JsonNode bundle, String subjectSha256, Instant now) {
        ArrayNode envelopes = array(bundle, "reviewEnvelopes", 2, 100);
        Map<String, Review> reviews = new HashMap<>();
        Set<String> reviewIds = new HashSet<>();
        Map<String, List<Review>> chains = new HashMap<>();
        for (JsonNode envelope : envelopes) {
            JsonNode untrusted = untrustedPayload(envelope);
            Instant issuedAt = instant(untrusted, "issuedAt");
            ViewOnlyPublicTrustStore.VerifiedDsse verified = trust.verifyCrossAi(
                    envelope, REVIEW_PAYLOAD_TYPE, "provider-review", issuedAt);
            JsonNode leaf = verified.payload();
            exactFields(leaf, REVIEW_FIELDS, "provider review");
            requireText(leaf, "schemaVersion", "acik.cross-ai-deployment-review.v2");
            requireText(leaf, "providerFamily", "openai");
            requireText(leaf, "channel", "openai-codex");
            requireText(leaf, "modelId", "gpt-5.6-sol");
            requireText(leaf, "modelIdentityClass", "trusted-launch-attested");
            requireBoolean(leaf, "directProviderCli", true);
            requireText(leaf, "reasoningEffort", "xhigh");
            requireText(leaf, "sandbox", "read-only");
            requireBoolean(leaf, "ephemeral", true);
            requireText(leaf, "issuer", "cross-ai-issuer-openai");
            requireText(leaf, "keyId", verified.keyId());
            requireText(leaf, "subjectSha256", subjectSha256);
            Instant expiresAt = instant(leaf, "expiresAt");
            if (issuedAt.isAfter(now.plusSeconds(30)) || !now.minusSeconds(30).isBefore(expiresAt)
                    || !issuedAt.isBefore(expiresAt)) {
                throw invalid("provider review is outside its active lifetime");
            }
            String reviewId = text(leaf, "reviewId");
            String reviewDigest = canonicalizer.digest(envelope);
            if (!reviewIds.add(reviewId) || reviews.containsKey(reviewDigest)
                    || trust.isCrossAiRevoked("review", reviewDigest, now)) {
                throw invalid("provider review is duplicate or revoked");
            }
            Review review = new Review(reviewDigest, leaf, issuedAt);
            reviews.put(review.digest(), review);
            chains.computeIfAbsent(text(leaf, "reviewChainId"), ignored -> new ArrayList<>()).add(review);
            requireFindingState(leaf);
        }
        for (Map.Entry<String, List<Review>> entry : chains.entrySet()) {
            List<Review> ordered = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(review -> integer(review.payload(), "round", 1, 100)))
                    .toList();
            for (int index = 0; index < ordered.size(); index++) {
                Review current = ordered.get(index);
                requireInteger(current.payload(), "round", index + 1);
                JsonNode predecessor = current.payload().get("previousRoundSha256");
                if (index == 0) {
                    if (predecessor == null || !predecessor.isNull()) {
                        throw invalid("review chain first round must not have a predecessor");
                    }
                } else if (predecessor == null || !predecessor.isTextual()
                        || !ordered.get(index - 1).digest().equals(predecessor.textValue())) {
                    throw invalid("review chain predecessor digest is invalid");
                }
            }
        }
        return reviews;
    }

    private String verifyClosure(JsonNode bundle, Map<String, Review> reviews, String subjectSha256) {
        JsonNode closure = object(bundle, "closure");
        exactFields(closure, Set.of("entries", "closureRootSha256"), "review closure");
        ArrayNode entries = array(closure, "entries", 0, 1000);
        List<JsonNode> sorted = new ArrayList<>();
        Set<String> findingIds = new HashSet<>();
        for (JsonNode entry : entries) {
            exactFields(entry, Set.of(
                    "findingId", "raisedByReviewSha256", "fixSha256", "acknowledgedByReviewSha256"),
                    "closure entry");
            String findingId = text(entry, "findingId");
            if (!findingIds.add(findingId)) {
                throw invalid("closure finding ID is duplicated");
            }
            Review raised = reviews.get(digestText(entry, "raisedByReviewSha256"));
            Review acknowledged = reviews.get(digestText(entry, "acknowledgedByReviewSha256"));
            digestText(entry, "fixSha256");
            if (raised == null || acknowledged == null
                    || "AGREE".equals(text(raised.payload(), "verdict"))
                    || !stringSet(raised.payload().get("findingIds"), "findingIds").contains(findingId)
                    || !stringSet(acknowledged.payload().get("resolvedFindingIds"), "resolvedFindingIds")
                            .contains(findingId)
                    || !stringSet(acknowledged.payload().get("acknowledgedFindingIds"), "acknowledgedFindingIds")
                            .contains(findingId)
                    || !acknowledged.issuedAt().isAfter(raised.issuedAt())) {
                throw invalid("closure does not bind a later same-provider acknowledgement");
            }
            sorted.add(entry.deepCopy());
        }
        Set<String> raisedIds = new HashSet<>();
        reviews.values().forEach(review -> {
            if (!"AGREE".equals(text(review.payload(), "verdict"))) {
                raisedIds.addAll(stringSet(review.payload().get("findingIds"), "findingIds"));
            }
        });
        if (!raisedIds.equals(findingIds)) {
            throw invalid("closure does not cover every raised finding");
        }
        sorted.sort(Comparator.comparing(node -> text(node, "findingId")));
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", CLOSURE_DOMAIN);
        projection.put("subjectSha256", subjectSha256);
        ArrayNode projectionEntries = projection.putArray("entries");
        sorted.forEach(projectionEntries::add);
        String root = canonicalizer.digest(projection);
        requireText(closure, "closureRootSha256", root);
        requireText(object(bundle, "consensus"), "closureRootSha256", root);
        return root;
    }

    private void verifyConsensus(JsonNode bundle,
                                 Map<String, Review> reviews,
                                 String subjectSha256,
                                 String closureRoot) {
        JsonNode consensus = object(bundle, "consensus");
        exactFields(consensus, Set.of(
                "providerFamilies", "finalAgreeReviewSha256", "closureRootSha256",
                "openMustFixFindingCount"), "v3 consensus");
        requireSingleTextArray(consensus.get("providerFamilies"), "openai", "consensus.providerFamilies");
        requireInteger(consensus, "openMustFixFindingCount", 0);
        ArrayNode finalDigests = array(consensus, "finalAgreeReviewSha256", 1, 1);
        String finalDigest = finalDigests.get(0).asText();
        Review selected = reviews.get(finalDigest);
        if (selected == null || !"AGREE".equals(text(selected.payload(), "verdict"))
                || !subjectSha256.equals(text(selected.payload(), "subjectSha256"))
                || !closureRoot.equals(text(selected.payload(), "closureRootSha256"))) {
            throw invalid("v3 consensus does not select one current Codex AGREE review");
        }
        String selectedChain = text(selected.payload(), "reviewChainId");
        if (reviews.values().stream().anyMatch(
                review -> !selectedChain.equals(text(review.payload(), "reviewChainId")))) {
            throw invalid("every v3 provider review must belong to the one selected Codex chain");
        }
        for (Review review : reviews.values()) {
            if (text(review.payload(), "reviewChainId").equals(text(selected.payload(), "reviewChainId"))
                    && integer(review.payload(), "round", 1, 100)
                    > integer(selected.payload(), "round", 1, 100)) {
                throw invalid("v3 consensus review is not its chain tip");
            }
        }
    }

    private void requireBinding(JsonNode binding,
                                JsonNode subject,
                                JsonNode stage,
                                JsonNode grant,
                                String authoritySetSha256,
                                String plainBundleEnvelopeSha256) {
        requireSame(binding, subject, "repositoryId");
        for (String field : Set.of(
                "repository", "environment", "deploymentClass", "productSlice", "intentRef", "headSha")) {
            requireText(binding, field, text(subject, field));
        }
        requireText(binding, "intentBundleSha256", plainBundleEnvelopeSha256);
        requireText(binding, "transactionSessionSha256", text(subject, "sessionSha256"));
        requireText(binding, "workflowPath", text(stage, "workflowPath"));
        requireText(binding, "workflowBlobSha256", text(stage, "workflowBlobSha256"));
        requireText(binding, "dependencyLockSha256", text(stage, "dependencyLockSha256"));
        requireText(binding, "concurrencySha256", text(stage, "concurrencyGroupSha256"));
        requireText(binding, "authoritySetSha256", authoritySetSha256);
        requireText(binding, "transactionScopeSha256", text(subject, "transactionScopeSha256"));
        requireSame(binding, grant, "triggeringActorId");
        Map<String, String> mapping = new LinkedHashMap<>();
        mapping.put("machineAuthorityPolicySha256", "policySha256");
        mapping.put("artifactSetSha256", "artifactSetSha256");
        mapping.put("rollbackPlanSha256", "rollbackPlanSha256");
        mapping.put("postDeployVerifierSha256", "postDeployVerifierSha256");
        mapping.put("bootstrapCredentialSha256", "bootstrapCredentialSha256");
        mapping.put("endpointIdSha256", "endpointIdSha256");
        mapping.put("operatorIdSha256", "operatorIdSha256");
        mapping.put("deviceHostnameSha256", "deviceHostnameSha256");
        mapping.put("attendedConsentPolicySha256", "attendedConsentPolicySha256");
        mapping.put("pilotOwnerPolicySha256", "pilotOwnerPolicySha256");
        mapping.put("maskPolicySha256", "maskPolicySha256");
        mapping.put("runtimeImageDigest", "runtimeImageDigest");
        mapping.put("runnerPolicySha256", "runnerPolicySha256");
        mapping.put("runnerAdmissionLeaseSha256", "runnerAdmissionLeaseSha256");
        mapping.forEach((bindingField, subjectField) ->
                requireText(binding, bindingField, text(subject, subjectField)));
        if (binding.get("pilotSeconds").intValue() != subject.get("pilotSeconds").intValue()) {
            throw invalid("binding pilotSeconds differs from v3 subject");
        }
    }

    private String authoritySetDigest(JsonNode filesValue) {
        if (filesValue == null || !filesValue.isArray() || filesValue.size() < 10 || filesValue.size() > 64) {
            throw invalid("transaction authority file inventory is outside its exact bound");
        }
        List<JsonNode> files = new ArrayList<>();
        Set<String> paths = new HashSet<>();
        for (JsonNode file : filesValue) {
            exactFields(file, Set.of("path", "sha256"), "transaction authority file");
            String path = text(file, "path");
            if (!paths.add(path)) {
                throw invalid("transaction authority file path is duplicated");
            }
            digestText(file, "sha256");
            files.add(file.deepCopy());
        }
        files.sort(Comparator.comparing(file -> text(file, "path")));
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", AUTHORITY_SET_DOMAIN);
        ArrayNode sorted = projection.putArray("files");
        files.forEach(sorted::add);
        return canonicalizer.digest(projection);
    }

    private static void requireFindingState(JsonNode leaf) {
        Set<String> raised = stringSet(leaf.get("findingIds"), "findingIds");
        Set<String> resolved = stringSet(leaf.get("resolvedFindingIds"), "resolvedFindingIds");
        Set<String> acknowledged = stringSet(leaf.get("acknowledgedFindingIds"), "acknowledgedFindingIds");
        String verdict = text(leaf, "verdict");
        if (!Set.of("AGREE", "REVISE", "RED", "PARTIAL").contains(verdict)
                || ("AGREE".equals(verdict) && (!raised.isEmpty() || !resolved.isEmpty() || !acknowledged.isEmpty()))
                || (Set.of("REVISE", "RED").contains(verdict) && raised.isEmpty())
                || ("PARTIAL".equals(verdict) && raised.isEmpty() && resolved.isEmpty() && acknowledged.isEmpty())
                || !acknowledged.stream().allMatch(resolved::contains)) {
            throw invalid("provider review finding transition is invalid");
        }
        for (String digestField : Set.of(
                "capabilitySnapshotSha256", "subjectSha256", "inputSha256", "outputSha256",
                "findingsSha256", "closureRootSha256")) {
            digestText(leaf, digestField);
        }
    }

    private JsonNode untrustedPayload(JsonNode envelope) {
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(text(envelope, "payload"));
            if (bytes.length == 0 || bytes.length > 524_288) {
                throw invalid("DSSE payload is outside its hard bound");
            }
            return canonicalizer.strictParse(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException invalidPayload) {
            throw invalid("DSSE payload is not strict base64 JSON", invalidPayload);
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
            throw invalid(label + " fields do not match the exact v3 contract");
        }
    }

    private static void exactStageFields(JsonNode stage) {
        if (stage == null || !stage.isObject()) {
            throw invalid("v3 transaction stage must be an object");
        }
        Set<String> actual = new HashSet<>();
        stage.fieldNames().forEachRemaining(actual::add);
        if (!actual.containsAll(STAGE_FIELDS)
                || actual.stream().anyMatch(field ->
                        !STAGE_FIELDS.contains(field) && !OPTIONAL_STAGE_FIELDS.contains(field))) {
            throw invalid("v3 transaction stage fields do not match the exact v3 contract");
        }
    }

    private static JsonNode object(JsonNode parent, String field) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isObject()) {
            throw invalid(field + " must be an object");
        }
        return value;
    }

    private static ArrayNode array(JsonNode parent, String field, int minimum, int maximum) {
        JsonNode value = parent == null ? null : parent.get(field);
        if (value == null || !value.isArray() || value.size() < minimum || value.size() > maximum) {
            throw invalid(field + " array is outside its exact bound");
        }
        return (ArrayNode) value;
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
            throw invalid(field + " does not match verified v3 authority");
        }
    }

    private static Instant instant(JsonNode object, String field) {
        try {
            return Instant.parse(text(object, field));
        } catch (Exception invalidTime) {
            throw invalid(field + " is not a canonical instant", invalidTime);
        }
    }

    private static long longValue(JsonNode object, String field) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw invalid(field + " is not a positive integer");
        }
        return value.longValue();
    }

    private static int integer(JsonNode object, String field, int minimum, int maximum) {
        JsonNode value = object.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()
                || value.intValue() < minimum || value.intValue() > maximum) {
            throw invalid(field + " is outside its integer bound");
        }
        return value.intValue();
    }

    private static void requireInteger(JsonNode object, String field, int expected) {
        if (integer(object, field, expected, expected) != expected) {
            throw invalid(field + " does not match its exact integer value");
        }
    }

    private static void requireBoolean(JsonNode object, String field, boolean expected) {
        JsonNode value = object.get(field);
        if (value == null || !value.isBoolean() || value.booleanValue() != expected) {
            throw invalid(field + " does not match its exact boolean value");
        }
    }

    private static void requireSame(JsonNode left, JsonNode right, String field) {
        if (left.get(field) == null || !left.get(field).equals(right.get(field))) {
            throw invalid(field + " differs across signed authorities");
        }
    }

    private static void requireSingleTextArray(JsonNode value, String expected, String label) {
        if (value == null || !value.isArray() || value.size() != 1
                || !value.get(0).isTextual() || !expected.equals(value.get(0).textValue())) {
            throw invalid(label + " does not contain its exact singleton");
        }
    }

    private static void requireNonEmptyStringArray(JsonNode value, String label) {
        if (value == null || !value.isArray() || value.isEmpty()) {
            throw invalid(label + " must be a non-empty array");
        }
        Set<String> seen = new HashSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.textValue().isBlank() || !seen.add(entry.textValue())) {
                throw invalid(label + " entries must be unique non-blank strings");
            }
        }
    }

    private static Set<String> stringSet(JsonNode value, String label) {
        if (value == null || !value.isArray() || value.size() > 200) {
            throw invalid(label + " must be a bounded string array");
        }
        Set<String> result = new HashSet<>();
        for (JsonNode entry : value) {
            if (!entry.isTextual() || entry.textValue().isBlank() || !result.add(entry.textValue())) {
                throw invalid(label + " entries must be unique non-blank strings");
            }
        }
        return result;
    }

    private static ViewOnlyAuthorityException invalid(String message) {
        return invalid(message, null);
    }

    private static ViewOnlyAuthorityException invalid(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.CONTRACT_INVALID, message, cause);
    }

    private record Review(String digest, JsonNode payload, Instant issuedAt) {
        Review {
            payload = payload.deepCopy();
        }

        @Override
        public JsonNode payload() {
            return payload.deepCopy();
        }
    }
}
