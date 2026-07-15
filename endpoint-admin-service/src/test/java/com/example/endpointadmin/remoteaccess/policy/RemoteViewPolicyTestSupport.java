package com.example.endpointadmin.remoteaccess.policy;

import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

public final class RemoteViewPolicyTestSupport {
    public static final Instant NOW = Instant.parse("2026-07-15T12:00:00Z");
    public static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    private RemoteViewPolicyTestSupport() {
    }

    public static Path fixture(String name) {
        try {
            return Path.of(RemoteViewPolicyTestSupport.class.getResource(
                    "/remote-view-policy/" + name).toURI());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static RemoteViewPolicyProperties properties(Path privateKey, Path publicKey) {
        return new RemoteViewPolicyProperties(true,
                fixture("tenant-policy.schema.json").toString(),
                fixture("baseline.schema.json").toString(),
                fixture("envelope.schema.json").toString(),
                fixture("baseline.json").toString(),
                300, "policy-key-2026-01",
                List.of(new RemoteViewPolicyProperties.SigningKey("policy-key-2026-01",
                        privateKey.toString(), publicKey.toString(), null)), Set.of());
    }

    public static KeyPair generateKeyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }
}
