package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.ValidationMessage;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Strict schema, lifecycle, digest and platform-floor validation. */
public final class RemoteViewPolicyValidator {
    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final RemoteViewPolicyArtifacts artifacts;
    private final String baselineDigest;

    public RemoteViewPolicyValidator(RemoteViewJsonCanonicalizer canonicalizer,
                                     RemoteViewPolicyArtifacts artifacts) {
        this.canonicalizer = canonicalizer;
        this.artifacts = artifacts;
        this.baselineDigest = canonicalizer.digest(artifacts.baseline());
    }

    public ValidatedRemoteViewPolicy validate(JsonNode source, UUID expectedTenant, Instant now) {
        return validate(source, expectedTenant, now, true);
    }

    public ValidatedRemoteViewPolicy validateForPublication(JsonNode source, UUID expectedTenant, Instant now) {
        return validate(source, expectedTenant, now, false);
    }

    private ValidatedRemoteViewPolicy validate(JsonNode source, UUID expectedTenant, Instant now,
                                               boolean requireEffectiveNow) {
        Set<ValidationMessage> violations = artifacts.tenantPolicySchema().validate(source);
        if (!violations.isEmpty()) {
            throw invalid("Tenant policy schema validation failed");
        }
        JsonNode baseline = artifacts.baseline();
        UUID tenant = parseUuid(source.path("tenantId").asText());
        if (expectedTenant == null || !expectedTenant.equals(tenant)) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_SESSION_MISMATCH,
                    "Tenant policy does not belong to the authenticated tenant");
        }
        String claimedBaseline = source.path("baseline").path("baselineDigest").asText();
        if (!baselineDigest.equals(claimedBaseline)
                || !baseline.path("baselineId").asText().equals(source.path("baseline").path("baselineId").asText())
                || !baseline.path("baselineVersion").asText()
                .equals(source.path("baseline").path("baselineVersion").asText())) {
            throw invalid("Tenant policy is not bound to the active platform baseline");
        }

        Instant validFrom = instant(source, "lifecycle", "validFrom");
        Instant validUntil = instant(source, "lifecycle", "validUntil");
        Instant reviewBy = instant(source, "lifecycle", "reviewBy");
        Instant legalReviewBy = instant(source, "legalEvidence", "reviewBy");
        Instant baselineValidFrom = instant(baseline, "lifecycle", "validFrom");
        Instant baselineReviewBy = instant(baseline, "lifecycle", "reviewBy");
        if (now.isBefore(baselineValidFrom) || !now.isBefore(baselineReviewBy)
                || (requireEffectiveNow && now.isBefore(validFrom))) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_STALE,
                    "Policy or baseline is not currently effective");
        }
        if (!now.isBefore(validUntil) || !now.isBefore(reviewBy) || !now.isBefore(legalReviewBy)) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_EXPIRED,
                    "Policy or legal review window has expired");
        }
        if (!validFrom.isBefore(reviewBy) || reviewBy.isAfter(validUntil)
                || !validFrom.isBefore(legalReviewBy) || legalReviewBy.isAfter(validUntil)) {
            throw invalid("Policy lifecycle is contradictory");
        }

        String legalStatus = source.path("legalEvidence").path("status").asText();
        if ("withdrawn".equals(legalStatus) || "expired".equals(legalStatus)) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_REVOKED,
                    "Legal evidence is withdrawn or expired");
        }
        String deploymentClass = source.path("deploymentClass").asText();
        if ("production".equals(deploymentClass) && !"approved".equals(legalStatus)) {
            throw invalid("Production policy requires approved legal evidence");
        }

        enforcePlatformLimits(source, baseline);
        JsonNode notice = selectAndVerifyNotice(source);
        String policyDigest = canonicalizer.digest(source);
        String legalDigest = canonicalizer.digest(source.path("legalEvidence"));
        return new ValidatedRemoteViewPolicy(source.deepCopy(), baseline.deepCopy(), notice.deepCopy(), tenant,
                source.path("policyId").asText(), source.path("policyVersion").asText(), deploymentClass,
                policyDigest, baselineDigest, legalDigest, legalStatus,
                validFrom, validUntil, reviewBy, legalReviewBy);
    }

    public String baselineDigest() {
        return baselineDigest;
    }

    private void enforcePlatformLimits(JsonNode source, JsonNode baseline) {
        JsonNode session = source.path("policy").path("session");
        JsonNode limits = baseline.path("limits");
        if (session.path("maxSessionTtlSeconds").longValue() > limits.path("maxSessionTtlSeconds").longValue()
                || session.path("maxViewers").longValue() > limits.path("maxViewers").longValue()) {
            throw invalid("Tenant session limits exceed the platform floor");
        }
        JsonNode retention = source.path("policy").path("retention");
        if (retention.path("screenContent").path("ttlSeconds").longValue()
                > limits.path("maxScreenContentRetentionSeconds").longValue()
                || retention.path("sessionMetadata").path("ttlSeconds").longValue()
                > limits.path("maxSessionMetadataRetentionSeconds").longValue()
                || retention.path("auditRecords").path("ttlSeconds").longValue()
                > limits.path("maxAuditRetentionSeconds").longValue()) {
            throw invalid("Tenant retention exceeds the platform floor");
        }
    }

    private JsonNode selectAndVerifyNotice(JsonNode source) {
        JsonNode notice = source.path("policy").path("notice");
        String locale = notice.path("defaultLocale").asText();
        Set<String> seen = new HashSet<>();
        JsonNode selected = null;
        for (JsonNode localization : notice.path("localizations")) {
            String candidate = localization.path("locale").asText();
            if (!seen.add(candidate)) {
                throw invalid("Notice locales must be unique");
            }
            ObjectNode digestProjection = localization.deepCopy();
            String claimed = digestProjection.remove("contentDigest").asText();
            String actual = canonicalizer.digest(digestProjection);
            if (!actual.equals(claimed)) {
                throw new RemoteViewPolicyException(RemoteViewPolicyReason.NOTICE_DIGEST_MISMATCH,
                        "Notice content digest does not match its source localization");
            }
            if (locale.equals(candidate)) {
                selected = localization;
            }
        }
        if (selected == null) {
            throw new RemoteViewPolicyException(RemoteViewPolicyReason.NOTICE_DIGEST_MISMATCH,
                    "Default notice locale is absent");
        }
        return selected;
    }

    private static Instant instant(JsonNode root, String object, String field) {
        try {
            return Instant.parse(root.path(object).path(field).asText());
        } catch (DateTimeParseException e) {
            throw invalid("Invalid UTC timestamp at " + object + "." + field);
        }
    }

    private static UUID parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("non-canonical UUID");
            }
            return parsed;
        } catch (IllegalArgumentException e) {
            throw invalid("Invalid policy tenant UUID");
        }
    }

    private static RemoteViewPolicyException invalid(String message) {
        return new RemoteViewPolicyException(RemoteViewPolicyReason.POLICY_INVALID, message);
    }
}
