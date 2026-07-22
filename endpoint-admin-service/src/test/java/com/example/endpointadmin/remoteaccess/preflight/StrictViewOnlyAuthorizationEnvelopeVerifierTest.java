package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StrictViewOnlyAuthorizationEnvelopeVerifierTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final String D1 = "sha256:" + "1".repeat(64);
    private static final String D2 = "sha256:" + "2".repeat(64);
    private static final String D3 = "sha256:" + "3".repeat(64);
    private static final String D4 = "sha256:" + "4".repeat(64);
    private static final String COORDINATOR = "vault-transit://cross-ai/coordinator#v1";
    private static final String RUNNER = "vault-transit://cross-ai/runner-management#v1";
    private static final String REVIEW = "vault-transit://cross-ai/openai#v1";

    private RemoteViewJsonCanonicalizer canonicalizer;
    private ViewOnlyPublicTrustStore trust;
    private Map<String, ViewOnlyPublicTrustStore.VerifiedDsse> verifiedByPlainDigest;

    @BeforeEach
    void setUp() {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        trust = mock(ViewOnlyPublicTrustStore.class);
        verifiedByPlainDigest = new HashMap<>();
        when(trust.verifyCrossAi(any(), anyString(), anyString(), any())).thenAnswer(invocation -> {
            JsonNode envelope = invocation.getArgument(0);
            ViewOnlyPublicTrustStore.VerifiedDsse verified =
                    verifiedByPlainDigest.get(canonicalizer.digest(envelope));
            if (verified == null) {
                throw new AssertionError("unregistered DSSE fixture");
            }
            return verified;
        });
        when(trust.isCrossAiRevoked(anyString(), anyString(), any())).thenReturn(false);
    }

    @Test
    void verifiesOneTransactionCodexChainAndExactFullBinding() {
        Fixture fixture = fixture();
        VerifiedViewOnlyAuthorization verified = verifier().verify(
                fixture.outerEnvelope(), fixture.binding(), NOW);

        assertThat(verified.payloadType()).isEqualTo(VerifiedViewOnlyAuthorization.PAYLOAD_TYPE);
        assertThat(verified.bindingSha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(verified.transactionIdSha256()).matches("sha256:[0-9a-f]{64}");
        assertThat(verified.expiresAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    void rejectsBindingDriftAndUnselectedSecondReviewChain() {
        Fixture drift = fixture();
        drift.binding().put("workflowBlobSha256", D4);
        assertThatThrownBy(() -> verifier().verify(drift.outerEnvelope(), drift.binding(), NOW))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("workflowBlobSha256");

        Fixture chain = fixture();
        JsonNode bundle = decode(chain.outerEnvelope());
        ArrayNode reviews = (ArrayNode) bundle.get("reviewEnvelopes");
        JsonNode secondPayload = decode(reviews.get(1));
        ((ObjectNode) secondPayload).put("reviewChainId", "123e4567-e89b-42d3-a456-426614174099");
        ObjectNode changed = fakeEnvelope(
                "application/vnd.acik.cross-ai-deployment-review.v2+json", secondPayload, REVIEW);
        reviews.set(1, changed);
        register(changed, secondPayload, REVIEW);
        ObjectNode outer = fakeEnvelope(VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, bundle, COORDINATOR);
        register(outer, bundle, COORDINATOR);
        chain.binding().put("intentBundleSha256", canonicalizer.digest(outer));

        assertThatThrownBy(() -> verifier().verify(outer, chain.binding(), NOW))
                .isInstanceOf(ViewOnlyAuthorityException.class);
    }

    @Test
    void acceptsSchemaOptionalRunnerAuthorityAndRejectsUnknownStageField() {
        Fixture optional = fixture(stage -> stage
                .put("runnerGroupId", 1234)
                .put("runnerAttestationClass", "acik-testai-deploy-v1"));
        assertThat(verifier().verify(optional.outerEnvelope(), optional.binding(), NOW)).isNotNull();

        Fixture unknown = fixture(stage -> stage.put("untrustedAuthority", true));
        assertThatThrownBy(() -> verifier().verify(unknown.outerEnvelope(), unknown.binding(), NOW))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .hasMessageContaining("transaction stage fields");
    }

    @Test
    void mapsAnExpiredGrantToTheDedicatedAuthorizationExpiredReason() {
        Fixture fixture = fixture();
        ObjectNode bundle = (ObjectNode) decode(fixture.outerEnvelope());
        ((ObjectNode) bundle.get("grant")).put("expiresAt", NOW.minusSeconds(31).toString());
        ObjectNode expired = fakeEnvelope(VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, bundle, COORDINATOR);
        register(expired, bundle, COORDINATOR);

        assertThatThrownBy(() -> verifier().verify(expired, fixture.binding(), NOW))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.AUTHORIZATION_EXPIRED);
    }

    private StrictViewOnlyAuthorizationEnvelopeVerifier verifier() {
        return new StrictViewOnlyAuthorizationEnvelopeVerifier(trust, canonicalizer);
    }

    private Fixture fixture() {
        return fixture(stage -> { });
    }

    private Fixture fixture(java.util.function.Consumer<ObjectNode> stageMutation) {
        ObjectNode stage = stage();
        stageMutation.accept(stage);
        String authoritySet = authoritySet(stage);
        ObjectNode subject = subject(authoritySet);
        ObjectNode grant = grant();
        subject.put("sessionSha256", session(subject, grant));

        ObjectNode runnerPayload = runnerLease(subject, grant);
        ObjectNode runnerEnvelope = fakeEnvelope(
                "application/vnd.acik.cross-ai-runner-admission-lease.v1+json", runnerPayload, RUNNER);
        register(runnerEnvelope, runnerPayload, RUNNER);
        subject.put("runnerAdmissionLeaseSha256", canonicalizer.digest(runnerEnvelope));

        ArrayNode stages = canonicalizer.mapper().createArrayNode().add(stage);
        ObjectNode subjectProjection = canonicalizer.mapper().createObjectNode();
        subjectProjection.set("subject", subject);
        subjectProjection.set("workflowStages", stages);
        subjectProjection.set("grant", grant);
        String subjectDigest = canonicalizer.digest(subjectProjection);

        String chainId = "123e4567-e89b-42d3-a456-426614174020";
        ObjectNode round1Payload = review(
                "123e4567-e89b-42d3-a456-426614174021", chainId, 1, "REVISE",
                null, subjectDigest, D1, new String[]{"FINDING_ONE"}, new String[]{}, new String[]{});
        ObjectNode round1 = fakeEnvelope(
                "application/vnd.acik.cross-ai-deployment-review.v2+json", round1Payload, REVIEW);
        register(round1, round1Payload, REVIEW);
        String round1Digest = canonicalizer.digest(round1);

        ObjectNode round2Payload = review(
                "123e4567-e89b-42d3-a456-426614174022", chainId, 2, "PARTIAL",
                round1Digest, subjectDigest, D1, new String[]{},
                new String[]{"FINDING_ONE"}, new String[]{"FINDING_ONE"});
        ObjectNode round2 = fakeEnvelope(
                "application/vnd.acik.cross-ai-deployment-review.v2+json", round2Payload, REVIEW);
        register(round2, round2Payload, REVIEW);
        String round2Digest = canonicalizer.digest(round2);

        ObjectNode closureEntry = canonicalizer.mapper().createObjectNode();
        closureEntry.put("findingId", "FINDING_ONE");
        closureEntry.put("raisedByReviewSha256", round1Digest);
        closureEntry.put("fixSha256", D2);
        closureEntry.put("acknowledgedByReviewSha256", round2Digest);
        ObjectNode closureProjection = canonicalizer.mapper().createObjectNode();
        closureProjection.put("domain", "acik.cross-ai-deployment-closure.v3");
        closureProjection.put("subjectSha256", subjectDigest);
        closureProjection.putArray("entries").add(closureEntry);
        String closureRoot = canonicalizer.digest(closureProjection);

        ObjectNode round3Payload = review(
                "123e4567-e89b-42d3-a456-426614174023", chainId, 3, "AGREE",
                round2Digest, subjectDigest, closureRoot,
                new String[]{}, new String[]{}, new String[]{});
        ObjectNode round3 = fakeEnvelope(
                "application/vnd.acik.cross-ai-deployment-review.v2+json", round3Payload, REVIEW);
        register(round3, round3Payload, REVIEW);

        ObjectNode bundle = canonicalizer.mapper().createObjectNode();
        bundle.put("schemaVersion", "acik.cross-ai-deployment-bundle.v3");
        bundle.put("bundleId", "123e4567-e89b-42d3-a456-426614174030");
        bundle.set("subject", subject);
        bundle.set("workflowStages", stages);
        bundle.set("runnerAdmissionLeaseEnvelope", runnerEnvelope);
        bundle.putArray("reviewEnvelopes").add(round1).add(round2).add(round3);
        ObjectNode closure = bundle.putObject("closure");
        closure.putArray("entries").add(closureEntry);
        closure.put("closureRootSha256", closureRoot);
        ObjectNode consensus = bundle.putObject("consensus");
        consensus.putArray("providerFamilies").add("openai");
        consensus.putArray("finalAgreeReviewSha256").add(canonicalizer.digest(round3));
        consensus.put("closureRootSha256", closureRoot);
        consensus.put("openMustFixFindingCount", 0);
        bundle.set("grant", grant);
        ObjectNode outer = fakeEnvelope(VerifiedViewOnlyAuthorization.PAYLOAD_TYPE, bundle, COORDINATOR);
        register(outer, bundle, COORDINATOR);

        ObjectNode binding = ViewOnlyTestFixtures.binding(canonicalizer, 186576227L, 29678094664L);
        binding.put("intentBundleSha256", canonicalizer.digest(outer));
        binding.put("transactionSessionSha256", subject.get("sessionSha256").textValue());
        binding.put("workflowBlobSha256", stage.get("workflowBlobSha256").textValue());
        binding.put("dependencyLockSha256", stage.get("dependencyLockSha256").textValue());
        binding.put("concurrencySha256", stage.get("concurrencyGroupSha256").textValue());
        binding.put("authoritySetSha256", authoritySet);
        binding.put("transactionScopeSha256", authoritySet);
        binding.put("runnerAdmissionLeaseSha256", subject.get("runnerAdmissionLeaseSha256").textValue());
        return new Fixture(outer, binding);
    }

    private ObjectNode subject(String authoritySet) {
        ObjectNode subject = canonicalizer.mapper().createObjectNode();
        subject.put("repositoryId", 1211415632L);
        subject.put("repository", "Halildeu/platform-k8s-gitops");
        subject.put("headSha", ViewOnlyTestFixtures.HEAD);
        subject.put("intentRef", ViewOnlyTestFixtures.REF);
        subject.put("environment", "faz22-view-only-pilot");
        subject.put("deploymentClass", "reversible-test");
        subject.put("productSlice", "Halildeu/platform-k8s-gitops#2373");
        subject.put("policySha256", D1);
        subject.put("artifactSetSha256", D1);
        subject.put("rollbackPlanSha256", D1);
        subject.put("postDeployVerifierSha256", D1);
        subject.put("runnerPolicySha256", D1);
        subject.put("runnerAdmissionLeaseSha256", D1);
        subject.put("bootstrapCredentialSha256", D1);
        subject.put("sessionSha256", D1);
        subject.put("endpointIdSha256", D1);
        subject.put("deviceHostnameSha256", D1);
        subject.put("operatorIdSha256", D1);
        subject.put("attendedConsentPolicySha256", D1);
        subject.put("pilotOwnerPolicySha256", D1);
        subject.put("maskPolicySha256", D1);
        subject.put("runtimeImageDigest", D1);
        subject.put("pilotSeconds", 900);
        subject.put("transactionScopeSha256", authoritySet);
        return subject;
    }

    private ObjectNode stage() {
        ObjectNode stage = canonicalizer.mapper().createObjectNode();
        stage.put("stage", "transaction");
        stage.put("order", 1);
        stage.put("workflowPath", ViewOnlyGithubOidcValidator.WORKFLOW_PATH);
        stage.put("workflowBlobSha256", D2);
        stage.put("dependencyLockSha256", D3);
        stage.put("concurrencyGroupSha256", D4);
        ArrayNode files = stage.putArray("authorityFiles");
        for (int index = 0; index < 10; index++) {
            files.addObject().put("path", "schema/test-" + index + ".json").put("sha256", D1);
        }
        stage.putArray("preflightRunsOnLabels").add("ubuntu-latest");
        stage.putArray("runsOnLabels").add("self-hosted").add("faz22-view-only");
        stage.put("maxUses", 1);
        stage.put("requiresSameRunPreflight", true);
        stage.put("requiresOneProtectedEnvironmentGate", true);
        return stage;
    }

    private String authoritySet(ObjectNode stage) {
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", "acik.cross-ai-transaction-authority-set.v1");
        ArrayNode sorted = projection.putArray("files");
        java.util.stream.StreamSupport.stream(stage.withArray("authorityFiles").spliterator(), false)
                .sorted(java.util.Comparator.comparing(node -> node.get("path").textValue()))
                .forEach(sorted::add);
        return canonicalizer.digest(projection);
    }

    private ObjectNode grant() {
        ObjectNode grant = canonicalizer.mapper().createObjectNode();
        grant.put("requestId", "123e4567-e89b-42d3-a456-426614174000");
        grant.put("deploymentSessionId", "123e4567-e89b-42d3-a456-426614174040");
        grant.put("transactionNonceSha256", D1);
        grant.put("triggeringActorId", 186576227L);
        grant.put("triggeringActorLogin", "Halildeu");
        grant.put("registrationPrincipal", "spiffe://acik/cross-ai/coordinator");
        grant.put("workflowEvent", "workflow_dispatch");
        grant.put("notBefore", NOW.minusSeconds(60).toString());
        grant.put("expiresAt", NOW.plusSeconds(1800).toString());
        grant.putArray("sequence").add("transaction");
        grant.put("failureTransition", "transaction->compensating-rollback-in-run");
        grant.put("authorizationMode", "dual-gate");
        grant.put("maxRunAttempts", 1);
        return grant;
    }

    private String session(JsonNode subject, JsonNode grant) {
        ObjectNode projection = canonicalizer.mapper().createObjectNode();
        projection.put("domain", "acik.cross-ai-deployment-session.v3");
        projection.put("requestId", grant.get("requestId").textValue());
        projection.put("deploymentSessionId", grant.get("deploymentSessionId").textValue());
        projection.set("repositoryId", subject.get("repositoryId"));
        for (String field : new String[]{"environment", "headSha", "intentRef", "bootstrapCredentialSha256",
                "endpointIdSha256", "operatorIdSha256", "deviceHostnameSha256", "pilotOwnerPolicySha256",
                "maskPolicySha256", "runtimeImageDigest", "pilotSeconds", "transactionScopeSha256"}) {
            projection.set(field, subject.get(field));
        }
        return canonicalizer.digest(projection);
    }

    private ObjectNode runnerLease(JsonNode subject, JsonNode grant) {
        ObjectNode lease = canonicalizer.mapper().createObjectNode();
        lease.put("schemaVersion", "acik.cross-ai-runner-admission-lease.v1");
        lease.put("leaseId", "123e4567-e89b-42d3-a456-426614174050");
        lease.set("requestId", grant.get("requestId"));
        for (String field : new String[]{"repositoryId", "repository", "environment", "headSha", "intentRef",
                "runnerPolicySha256"}) {
            lease.set(field, subject.get(field));
        }
        lease.put("inventoryGenerationSha256", D1);
        lease.put("issuedAt", NOW.minusSeconds(120).toString());
        lease.put("expiresAt", NOW.plusSeconds(1800).toString());
        ObjectNode runner = lease.putArray("eligibleRunners").addObject();
        runner.put("runnerId", 1L);
        runner.put("runnerNameSha256", D1);
        runner.putArray("labels").add("self-hosted");
        runner.put("attestationClass", "faz22-view-only");
        return lease;
    }

    private ObjectNode review(String reviewId,
                              String chainId,
                              int round,
                              String verdict,
                              String previous,
                              String subjectDigest,
                              String closureRoot,
                              String[] findings,
                              String[] resolved,
                              String[] acknowledged) {
        ObjectNode review = canonicalizer.mapper().createObjectNode();
        review.put("schemaVersion", "acik.cross-ai-deployment-review.v2");
        review.put("reviewId", reviewId);
        review.put("reviewChainId", chainId);
        review.put("providerFamily", "openai");
        review.put("channel", "openai-codex");
        review.put("directProviderCli", true);
        review.put("modelId", "gpt-5.6-sol");
        review.put("modelIdentityClass", "trusted-launch-attested");
        review.put("reasoningEffort", "xhigh");
        review.put("sandbox", "read-only");
        review.put("ephemeral", true);
        review.put("capabilitySnapshotSha256", D1);
        review.put("subjectSha256", subjectDigest);
        review.put("round", round);
        review.put("verdict", verdict);
        review.put("inputSha256", D1);
        review.put("outputSha256", D1);
        review.put("findingsSha256", D1);
        if (previous == null) {
            review.putNull("previousRoundSha256");
        } else {
            review.put("previousRoundSha256", previous);
        }
        add(review.putArray("findingIds"), findings);
        add(review.putArray("resolvedFindingIds"), resolved);
        add(review.putArray("acknowledgedFindingIds"), acknowledged);
        review.put("closureRootSha256", closureRoot);
        review.put("issuedAt", NOW.minusSeconds(50 - round).toString());
        review.put("expiresAt", NOW.plusSeconds(1800).toString());
        review.put("issuer", "cross-ai-issuer-openai");
        review.put("keyId", REVIEW);
        return review;
    }

    private ObjectNode fakeEnvelope(String payloadType, JsonNode payload, String keyId) {
        ObjectNode envelope = canonicalizer.mapper().createObjectNode();
        envelope.put("payloadType", payloadType);
        envelope.put("payload", Base64.getEncoder().encodeToString(canonicalizer.canonicalBytes(payload)));
        envelope.putArray("signatures").addObject().put("keyid", keyId)
                .put("sig", Base64.getEncoder().encodeToString(new byte[64]));
        return envelope;
    }

    private void register(ObjectNode envelope, JsonNode payload, String keyId) {
        verifiedByPlainDigest.put(canonicalizer.digest(envelope),
                new ViewOnlyPublicTrustStore.VerifiedDsse(payload, D4, keyId));
    }

    private JsonNode decode(JsonNode envelope) {
        return canonicalizer.strictParse(new String(
                Base64.getDecoder().decode(envelope.get("payload").textValue()),
                java.nio.charset.StandardCharsets.UTF_8));
    }

    private static void add(ArrayNode array, String[] values) {
        for (String value : values) {
            array.add(value);
        }
    }

    private record Fixture(ObjectNode outerEnvelope, ObjectNode binding) {
    }
}
