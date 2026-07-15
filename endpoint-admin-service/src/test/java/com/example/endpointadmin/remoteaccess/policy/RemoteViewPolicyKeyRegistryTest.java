package com.example.endpointadmin.remoteaccess.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteViewPolicyKeyRegistryTest {

    @Test
    void policyEnvelopeTtlBelowTheOperationalFloorIsRejected() {
        assertThatThrownBy(() -> new RemoteViewPolicyProperties(true, "tenant", "baseline-schema", "envelope",
                "baseline", 59, "active", List.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 60 seconds");
    }
    @TempDir
    Path temp;

    @Test
    void signsWithActiveKeyAndVerifiesDuringRotationOverlap() throws Exception {
        var active = RemoteViewPolicyTestSupport.generateKeyPair();
        var old = RemoteViewPolicyTestSupport.generateKeyPair();
        Path activePrivate = write("active-private.der", active.getPrivate().getEncoded());
        Path activePublic = write("active-public.der", active.getPublic().getEncoded());
        Path oldPublic = write("old-public.der", old.getPublic().getEncoded());
        var properties = new RemoteViewPolicyProperties(true, "a", "b", "c", "d", 300,
                "active-key", List.of(
                new RemoteViewPolicyProperties.SigningKey("active-key", activePrivate.toString(),
                        activePublic.toString(), null),
                new RemoteViewPolicyProperties.SigningKey("old-key", null, oldPublic.toString(),
                        "2026-08-01T00:00:00Z")), Set.of());
        RemoteViewPolicyKeyRegistry registry = new RemoteViewPolicyKeyRegistry(properties);
        byte[] payload = "same-session-payload".getBytes(StandardCharsets.UTF_8);
        var signed = registry.sign(payload);
        assertThat(signed.keyId()).isEqualTo("active-key");
        assertThat(registry.verify(signed.keyId(), payload, signed.signatureBase64(),
                RemoteViewPolicyTestSupport.NOW)).isTrue();
        assertThat(registry.verify("unknown", payload, signed.signatureBase64(),
                RemoteViewPolicyTestSupport.NOW)).isFalse();
        assertThat(registry.verify(signed.keyId(), "tampered".getBytes(StandardCharsets.UTF_8),
                signed.signatureBase64(), RemoteViewPolicyTestSupport.NOW)).isFalse();
    }

    @Test
    void refusesRevokedActiveKeyAndUnboundedOverlapKey() throws Exception {
        var pair = RemoteViewPolicyTestSupport.generateKeyPair();
        Path privateKey = write("private.der", pair.getPrivate().getEncoded());
        Path publicKey = write("public.der", pair.getPublic().getEncoded());
        var revoked = new RemoteViewPolicyProperties(true, "a", "b", "c", "d", 300,
                "key-1", List.of(new RemoteViewPolicyProperties.SigningKey("key-1", privateKey.toString(),
                publicKey.toString(), null)), Set.of("key-1"));
        assertThatThrownBy(() -> new RemoteViewPolicyKeyRegistry(revoked))
                .isInstanceOf(IllegalStateException.class).hasRootCauseMessage(
                        "remote-view-policy active key is revoked");

        var overlapWithoutExpiry = new RemoteViewPolicyProperties(true, "a", "b", "c", "d", 300,
                "key-1", List.of(
                new RemoteViewPolicyProperties.SigningKey("key-1", privateKey.toString(),
                        publicKey.toString(), null),
                new RemoteViewPolicyProperties.SigningKey("key-2", null, publicKey.toString(), null)), Set.of());
        assertThatThrownBy(() -> new RemoteViewPolicyKeyRegistry(overlapWithoutExpiry))
                .isInstanceOf(IllegalStateException.class)
                .hasRootCauseMessage("overlap key key-2 requires verify-until");
    }

    private Path write(String name, byte[] bytes) throws Exception {
        Path path = temp.resolve(name);
        Files.write(path, bytes);
        return path;
    }
}
