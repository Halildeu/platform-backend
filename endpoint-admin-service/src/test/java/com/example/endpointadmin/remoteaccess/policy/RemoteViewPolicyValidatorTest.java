package com.example.endpointadmin.remoteaccess.policy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteViewPolicyValidatorTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000245");
    private final RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();
    private RemoteViewPolicyValidator validator;
    private JsonNode source;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        var pair = RemoteViewPolicyTestSupport.generateKeyPair();
        Path privateKey = temp.resolve("private.der");
        Path publicKey = temp.resolve("public.der");
        Files.write(privateKey, pair.getPrivate().getEncoded());
        Files.write(publicKey, pair.getPublic().getEncoded());
        var properties = RemoteViewPolicyTestSupport.properties(privateKey, publicKey);
        validator = new RemoteViewPolicyValidator(canonicalizer,
                RemoteViewPolicyArtifacts.load(properties, canonicalizer));
        source = canonicalizer.strictParse(Files.readString(
                RemoteViewPolicyTestSupport.fixture("example-policy.json")));
    }

    @Test
    void rederivesAllLoadBearingDigests() {
        ValidatedRemoteViewPolicy validated = validator.validate(source, TENANT, RemoteViewPolicyTestSupport.NOW);
        assertThat(validated.policyDigest())
                .isEqualTo("sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4");
        assertThat(validated.baselineDigest())
                .isEqualTo("sha256:54132a1b4d035db7011f1ce200433234aea7fe0d04420f0928dcf26a06386337");
        assertThat(validated.legalEvidenceDigest())
                .isEqualTo("sha256:41fe3b5dc79dee11062ef9708de454ee78117cdcde55de427e8c301cf7cd609b");
        assertThat(validated.selectedNotice().path("locale").asText()).isEqualTo("tr-TR");
    }

    @Test
    void rejectsCrossTenantReplayAndNoticeDigestDrift() {
        assertReason(() -> validator.validate(source, UUID.randomUUID(), RemoteViewPolicyTestSupport.NOW),
                RemoteViewPolicyReason.POLICY_SESSION_MISMATCH);
        JsonNode drifted = source.deepCopy();
        ((ObjectNode) drifted.path("policy").path("notice").path("localizations").get(0))
                .put("body", "Bu metin yeterince uzun fakat digest artık doğru değildir ve oturum reddedilmelidir.");
        assertReason(() -> validator.validate(drifted, TENANT, RemoteViewPolicyTestSupport.NOW),
                RemoteViewPolicyReason.NOTICE_DIGEST_MISMATCH);
    }

    @Test
    void rejectsExpiredAndWithdrawnPolicy() {
        assertReason(() -> validator.validate(source, TENANT,
                        java.time.Instant.parse("2026-11-01T00:00:00Z")),
                RemoteViewPolicyReason.POLICY_EXPIRED);
        JsonNode withdrawn = source.deepCopy();
        ((ObjectNode) withdrawn.path("legalEvidence")).put("status", "withdrawn");
        assertReason(() -> validator.validate(withdrawn, TENANT, RemoteViewPolicyTestSupport.NOW),
                RemoteViewPolicyReason.POLICY_REVOKED);
    }

    @Test
    void rejectsAReviewWindowThatEndsBeforeThePolicyBecomesEffective() {
        JsonNode contradictory = source.deepCopy();
        ((ObjectNode) contradictory.path("lifecycle"))
                .put("validFrom", "2026-09-01T00:00:00Z")
                .put("reviewBy", "2026-08-01T00:00:00Z");

        assertReason(() -> validator.validateForPublication(
                        contradictory, TENANT, RemoteViewPolicyTestSupport.NOW),
                RemoteViewPolicyReason.POLICY_INVALID);
    }

    private static void assertReason(Runnable action, RemoteViewPolicyReason reason) {
        assertThatThrownBy(action::run)
                .isInstanceOf(RemoteViewPolicyException.class)
                .extracting(e -> ((RemoteViewPolicyException) e).reason())
                .isEqualTo(reason);
    }
}
