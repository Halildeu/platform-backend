package com.example.endpointadmin.remoteaccess.policy;

import com.example.endpointadmin.model.RemoteViewPolicyPublication;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.repository.RemoteViewPolicyPublicationRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyRevocationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/** Resolves and signs a fresh, non-cacheable same-session policy envelope. */
public final class RemoteViewSessionPolicyResolver {
    public static final String AGENT_FEATURE = "remote-view-session-policy-envelope-v1";

    private final RemoteViewPolicyPublicationRepository publications;
    private final RemoteViewPolicyRevocationRepository revocations;
    private final RemoteViewPolicyValidator validator;
    private final RemoteViewPolicyArtifacts artifacts;
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final RemoteViewPolicyKeyRegistry keys;
    private final RemoteViewPolicyProperties properties;
    private final Clock clock;
    private final SecureRandom random;

    public RemoteViewSessionPolicyResolver(RemoteViewPolicyPublicationRepository publications,
                                           RemoteViewPolicyRevocationRepository revocations,
                                           RemoteViewPolicyValidator validator,
                                           RemoteViewPolicyArtifacts artifacts,
                                           RemoteViewJsonCanonicalizer canonicalizer,
                                           RemoteViewPolicyKeyRegistry keys,
                                           RemoteViewPolicyProperties properties,
                                           Clock clock, SecureRandom random) {
        this.publications = publications;
        this.revocations = revocations;
        this.validator = validator;
        this.artifacts = artifacts;
        this.canonicalizer = canonicalizer;
        this.keys = keys;
        this.properties = properties;
        this.clock = clock;
        this.random = random;
    }

    public SignedRemoteViewSessionPolicy resolveAndSign(RemoteBridgeSession session) {
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        UUID tenant;
        try {
            tenant = UUID.fromString(session.operatorTenantId());
            if (!tenant.toString().equals(session.operatorTenantId())) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
        } catch (IllegalArgumentException e) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_SESSION_MISMATCH,
                    "Session tenant is not canonical", e);
        }
        RemoteViewPolicyPublication publication = publications.findEffective(tenant, now)
                .or(() -> publications.findLatest(tenant))
                .orElseThrow(() -> new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_UNAVAILABLE,
                        "No remote-view policy publication exists for the tenant"));
        if (revocations.findByTenantIdAndPublicationId(tenant, publication.getId()).isPresent()) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_REVOKED,
                    "The effective remote-view policy publication is revoked");
        }
        JsonNode source = canonicalizer.parseCanonical(publication.getCanonicalSource());
        ValidatedRemoteViewPolicy policy = validator.validate(source, tenant, now);
        if (!publication.getPolicyDigest().equals(policy.policyDigest())
                || !publication.getBaselineDigest().equals(policy.baselineDigest())
                || !publication.getLegalEvidenceDigest().equals(policy.legalEvidenceDigest())) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_INVALID,
                    "Published policy digest lineage is inconsistent");
        }

        long policyTtl = source.path("policy").path("session").path("maxSessionTtlSeconds").longValue();
        long baselineTtl = policy.baseline().path("limits").path("maxEnvelopeLifetimeSeconds").longValue();
        long promptRemaining = Math.max(0, (session.promptExpiryEpochMillis() - now.toEpochMilli()) / 1000);
        long ttl = Math.min(Math.min(properties.envelopeTtlSeconds(), policyTtl),
                Math.min(baselineTtl, promptRemaining));
        if (ttl < 60) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_EXPIRED,
                    "Insufficient policy-envelope lifetime remains");
        }
        Instant expiresAt = now.plusSeconds(ttl);
        ObjectNode envelope = buildEnvelope(session, policy, now, expiresAt, ttl);
        ObjectNode integrity = (ObjectNode) envelope.path("integrity");
        ObjectNode projection = envelope.deepCopy();
        ObjectNode projectionIntegrity = (ObjectNode) projection.path("integrity");
        projectionIntegrity.remove("payloadDigest");
        projectionIntegrity.remove("signatureBase64");
        byte[] payload = canonicalizer.canonicalBytes(projection);
        String payloadDigest = canonicalizer.digest(payload);
        RemoteViewPolicyKeyRegistry.SignedPayload signed = keys.sign(payload);
        if (!keys.verify(signed.keyId(), payload, signed.signatureBase64(), now)) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_SIGNATURE_FAILED,
                    "Policy envelope signing self-check failed");
        }
        integrity.put("payloadDigest", payloadDigest);
        integrity.put("signatureBase64", signed.signatureBase64());
        Set<ValidationMessage> violations = artifacts.envelopeSchema().validate(envelope);
        if (!violations.isEmpty()) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_INVALID,
                    "Resolved session policy envelope violates its schema");
        }
        String canonicalEnvelope = canonicalizer.canonicalString(envelope);
        return new SignedRemoteViewSessionPolicy(canonicalEnvelope,
                canonicalizer.digest(canonicalEnvelope.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                payloadDigest, signed.keyId(), policy.policyDigest());
    }

    private ObjectNode buildEnvelope(RemoteBridgeSession session, ValidatedRemoteViewPolicy policy,
                                     Instant issuedAt, Instant expiresAt, long ttl) {
        JsonNode source = policy.source();
        ObjectNode envelope = canonicalizer.mapper().createObjectNode();
        envelope.put("schemaVersion", "remote-view-session-policy-envelope-v1");
        envelope.put("envelopeId", "rvpe:" + UUID.randomUUID());
        envelope.put("deploymentClass", policy.deploymentClass());

        ObjectNode sessionNode = envelope.putObject("session");
        sessionNode.put("sessionId", session.sessionId());
        sessionNode.put("tenantId", session.operatorTenantId());
        sessionNode.put("deviceId", session.deviceId());
        sessionNode.put("issuedAt", issuedAt.toString());
        sessionNode.put("expiresAt", expiresAt.toString());
        sessionNode.put("ttlSeconds", ttl);
        byte[] nonce = new byte[32];
        random.nextBytes(nonce);
        sessionNode.put("nonceBase64", Base64.getEncoder().encodeToString(nonce));

        ObjectNode policyNode = envelope.putObject("policy");
        policyNode.put("policyId", policy.policyId());
        policyNode.put("policyVersion", policy.policyVersion());
        policyNode.put("policyDigest", policy.policyDigest());
        policyNode.put("sourcePolicyRef", "urn:remote-view-tenant-policy:" + policy.policyDigest());
        policyNode.put("baselineId", policy.baseline().path("baselineId").asText());
        policyNode.put("baselineVersion", policy.baseline().path("baselineVersion").asText());
        policyNode.put("baselineDigest", policy.baselineDigest());
        policyNode.put("legalEvidenceStatus", policy.legalEvidenceStatus());
        policyNode.put("legalEvidenceDigest", policy.legalEvidenceDigest());

        JsonNode invariants = policy.baseline().path("invariants");
        ObjectNode enforcement = envelope.putObject("enforcement");
        copy(enforcement, invariants, "mode");
        for (String field : new String[]{"attendedConsentRequired", "autoConsentAllowed", "screenViewAllowed",
                "keyboardInputAllowed", "mouseInputAllowed", "clipboardAllowed", "fileTransferAllowed",
                "tunnelAllowed", "visibleIndicatorRequired", "localAbortRequired",
                "maskBeforeFrameEmissionRequired", "denyOnMaskFailure"}) {
            copy(enforcement, invariants, field);
        }
        enforcement.put("maxViewers", source.path("policy").path("session").path("maxViewers").longValue());
        enforcement.put("recordingMode", source.path("policy").path("recording").path("mode").asText());

        JsonNode selected = policy.selectedNotice();
        ObjectNode notice = envelope.putObject("notice");
        notice.put("noticeVersion", source.path("policy").path("notice").path("noticeVersion").asText());
        for (String field : new String[]{"locale", "title", "body", "allowLabel", "denyLabel",
                "withdrawalText", "localAbortText", "contentDigest"}) {
            copy(notice, selected, field);
        }

        JsonNode retention = source.path("policy").path("retention");
        JsonNode governance = source.path("policy").path("dataGovernance");
        ObjectNode data = envelope.putObject("dataHandling");
        data.put("screenContentTtlSeconds", retention.path("screenContent").path("ttlSeconds").longValue());
        data.put("sessionMetadataTtlSeconds", retention.path("sessionMetadata").path("ttlSeconds").longValue());
        data.put("auditTtlSeconds", retention.path("auditRecords").path("ttlSeconds").longValue());
        ArrayNode regions = data.putArray("storageRegions");
        governance.path("storageRegions").forEach(region -> regions.add(region.asText()));
        data.put("crossBorderTransfer", governance.path("crossBorderTransfer").asText());
        data.put("specialCategoryAction",
                source.path("policy").path("specialCategory").path("defaultAction").asText());

        ObjectNode integrity = envelope.putObject("integrity");
        integrity.put("canonicalization", "JCS-RFC8785");
        integrity.put("signatureAlgorithm", RemoteViewPolicyKeyRegistry.ALGORITHM);
        integrity.put("keyId", keys.activeKeyId());
        integrity.put("payloadDigest", "");
        integrity.put("signatureBase64", "");
        return envelope;
    }

    private static void copy(ObjectNode target, JsonNode source, String field) {
        target.set(field, source.path(field).deepCopy());
    }
}
