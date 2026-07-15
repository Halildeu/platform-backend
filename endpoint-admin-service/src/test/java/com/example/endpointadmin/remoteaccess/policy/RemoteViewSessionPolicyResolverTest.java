package com.example.endpointadmin.remoteaccess.policy;

import com.example.endpointadmin.model.RemoteViewPolicyPublication;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSession;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteBridgeSessionStore;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.RemoteViewPolicyPublicationRepository;
import com.example.endpointadmin.repository.RemoteViewPolicyRevocationRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RemoteViewSessionPolicyResolverTest {
    private static final UUID TENANT = UUID.fromString("00000000-0000-4000-8000-000000000245");
    private RemoteViewJsonCanonicalizer canonicalizer;
    private RemoteViewPolicyKeyRegistry keys;
    private RemoteViewSessionPolicyResolver resolver;
    private RemoteViewPolicyPublicationRepository publications;
    private RemoteViewPolicyRevocationRepository revocations;
    private RemoteViewPolicyPublication publication;

    @BeforeEach
    void setUp(@TempDir Path temp) throws Exception {
        canonicalizer = new RemoteViewJsonCanonicalizer();
        var pair = RemoteViewPolicyTestSupport.generateKeyPair();
        Path privateKey = temp.resolve("private.der");
        Path publicKey = temp.resolve("public.der");
        Files.write(privateKey, pair.getPrivate().getEncoded());
        Files.write(publicKey, pair.getPublic().getEncoded());
        RemoteViewPolicyProperties properties = RemoteViewPolicyTestSupport.properties(privateKey, publicKey);
        RemoteViewPolicyArtifacts artifacts = RemoteViewPolicyArtifacts.load(properties, canonicalizer);
        RemoteViewPolicyValidator validator = new RemoteViewPolicyValidator(canonicalizer, artifacts);
        keys = new RemoteViewPolicyKeyRegistry(properties);
        publications = mock(RemoteViewPolicyPublicationRepository.class);
        revocations = mock(RemoteViewPolicyRevocationRepository.class);
        String source = canonicalizer.canonicalString(canonicalizer.strictParse(Files.readString(
                RemoteViewPolicyTestSupport.fixture("example-policy.json"))));
        publication = new RemoteViewPolicyPublication(UUID.randomUUID(), UUID.randomUUID(), TENANT,
                "example-tr-domestic-view-only", "1.0.0", "bounded-test", source,
                "sha256:932397b4844474922392324a00c84457db026a5d00de837f1fd2daf8985c86d4",
                "sha256:54132a1b4d035db7011f1ce200433234aea7fe0d04420f0928dcf26a06386337",
                "sha256:41fe3b5dc79dee11062ef9708de454ee78117cdcde55de427e8c301cf7cd609b",
                "tracked-pending", null, Instant.parse("2026-07-15T00:00:00Z"),
                Instant.parse("2026-10-15T00:00:00Z"), Instant.parse("2026-10-01T00:00:00Z"),
                Instant.parse("2026-10-01T00:00:00Z"), "publisher", RemoteViewPolicyTestSupport.NOW);
        when(publications.findEffective(TENANT, RemoteViewPolicyTestSupport.NOW)).thenReturn(Optional.of(publication));
        when(revocations.findByTenantIdAndPublicationId(TENANT, publication.getId())).thenReturn(Optional.empty());
        resolver = new RemoteViewSessionPolicyResolver(publications, revocations, validator, artifacts, canonicalizer, keys,
                properties, RemoteViewPolicyTestSupport.CLOCK, new java.security.SecureRandom());
    }

    @Test
    void signsFreshEnvelopeBoundToTheExactSessionAndPlatformFloor() {
        RemoteBridgeSession session = openSession();
        SignedRemoteViewSessionPolicy signed = resolver.resolveAndSign(session);
        JsonNode envelope = canonicalizer.strictParse(signed.canonicalEnvelopeJson());
        assertThat(envelope.path("session").path("sessionId").asText()).isEqualTo("session-policy-1");
        assertThat(envelope.path("session").path("tenantId").asText()).isEqualTo(TENANT.toString());
        assertThat(envelope.path("session").path("deviceId").asText()).isEqualTo("device-policy-1");
        assertThat(envelope.path("session").path("ttlSeconds").longValue()).isEqualTo(120);
        assertThat(envelope.path("enforcement").path("autoConsentAllowed").asBoolean()).isFalse();
        assertThat(envelope.path("enforcement").path("keyboardInputAllowed").asBoolean()).isFalse();
        assertThat(envelope.path("enforcement").path("maskBeforeFrameEmissionRequired").asBoolean()).isTrue();
        assertThat(envelope.path("enforcement").path("denyOnMaskFailure").asBoolean()).isTrue();
        assertThat(envelope.path("notice").path("locale").asText()).isEqualTo("tr-TR");

        ObjectNode projection = envelope.deepCopy();
        ObjectNode integrity = (ObjectNode) projection.path("integrity");
        String signature = integrity.remove("signatureBase64").asText();
        integrity.remove("payloadDigest");
        byte[] payload = canonicalizer.canonicalBytes(projection);
        assertThat(canonicalizer.digest(payload)).isEqualTo(signed.payloadDigest());
        assertThat(keys.verify(signed.keyId(), payload, signature, RemoteViewPolicyTestSupport.NOW)).isTrue();
        assertThat(canonicalizer.digest(signed.canonicalEnvelopeJson()
                .getBytes(java.nio.charset.StandardCharsets.UTF_8))).isEqualTo(signed.envelopeDigest());
    }

    @Test
    void everyResolutionMintsANewEnvelopeAndNonce() {
        SignedRemoteViewSessionPolicy first = resolver.resolveAndSign(openSession());
        RemoteBridgeSessionStore secondStore = new RemoteBridgeSessionStore();
        RemoteBridgeSession second = ((RemoteBridgeSessionStore.Opened) secondStore.open(
                new RemoteBridgeMessages.SessionRequest("session-policy-2", "device-policy-1", "operator-1",
                        "support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity("peer-policy-2", Optional.empty(), List.of()), TENANT.toString(), "Operator",
                RemoteViewPolicyTestSupport.NOW.plusSeconds(120).toEpochMilli(),
                RemoteViewPolicyTestSupport.NOW.toEpochMilli())).session();
        SignedRemoteViewSessionPolicy next = resolver.resolveAndSign(second);
        assertThat(next.envelopeDigest()).isNotEqualTo(first.envelopeDigest());
        assertThat(canonicalizer.strictParse(next.canonicalEnvelopeJson()).path("session").path("nonceBase64").asText())
                .isNotEqualTo(canonicalizer.strictParse(first.canonicalEnvelopeJson())
                        .path("session").path("nonceBase64").asText());
    }

    @Test
    void revokedEffectivePublicationNeverFallsBackToAnOlderPolicy() {
        when(revocations.findByTenantIdAndPublicationId(TENANT, publication.getId()))
                .thenReturn(Optional.of(mock(com.example.endpointadmin.model.RemoteViewPolicyRevocation.class)));

        assertThatThrownBy(() -> resolver.resolveAndSign(openSession()))
                .isInstanceOfSatisfying(RemoteViewPolicyException.class,
                        exception -> assertThat(exception.reason()).isEqualTo(RemoteViewPolicyReason.POLICY_REVOKED));
    }

    private RemoteBridgeSession openSession() {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        return ((RemoteBridgeSessionStore.Opened) store.open(
                new RemoteBridgeMessages.SessionRequest("session-policy-1", "device-policy-1", "operator-1",
                        "support", Set.of(RemoteSessionCapability.VIEW_ONLY)),
                new PeerIdentity("peer-policy-1", Optional.empty(), List.of()), TENANT.toString(), "Operator",
                RemoteViewPolicyTestSupport.NOW.plusSeconds(120).toEpochMilli(),
                RemoteViewPolicyTestSupport.NOW.toEpochMilli())).session();
    }
}
