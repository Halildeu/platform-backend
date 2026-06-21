package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitSigner;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgePermitVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Faz 22.6 T-4a-ii slice-1 (Codex 019ebc7e) — the permit-signing key is loaded fail-closed at context init:
 * an enabled bridge that cannot sign permits refuses to start (no insecure escape — unlike the transport-TLS
 * smoke flag). The eager signer bean runs the key validation at refresh, so the acceptance is "enabled boot
 * requires a valid PKCS#8 EC P-256 signing key + kid", proven without yet wiring the broker.
 */
class RemoteBridgePermitSigningConfigTest {

    @TempDir
    static Path tempDir;

    // slice-3c: the enabled config now also wires the broker (durable sink needs a JdbcTemplate) — a mock
    // datasource stands in; the signer bean inits FIRST, so a missing-signer test still fails on the signer.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
            .withBean(EndpointMachineCertRepository.class, () -> mock(EndpointMachineCertRepository.class))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new) // bridge data-plane metrics (T-2b/#1588)
            .withUserConfiguration(RemoteBridgeServerConfig.class);

    private static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(new ECGenParameterSpec(curve));
        return g.generateKeyPair();
    }

    /** Write a PKCS#8 ("BEGIN PRIVATE KEY") PEM of the key pair's private key. */
    private static Path pkcs8Pem(String name, KeyPair kp) throws Exception {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded());
        String pem = "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n";
        Path file = tempDir.resolve(name);
        Files.writeString(file, pem);
        return file;
    }

    // The TLS escape so the GrpcServer's mTLS gate doesn't mask the signer behavior under test.
    private ApplicationContextRunner enabledLoopback(KeyPair signingKey, String kid, Path keyPath) {
        return runner.withPropertyValues(
                "remote-bridge.enabled=true",
                "remote-bridge.bind-host=127.0.0.1",
                "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.heartbeat-interval-millis=0",
                "remote-bridge.permit.signing-key-pem-path=" + (keyPath == null ? "" : keyPath),
                "remote-bridge.permit.kid=" + (kid == null ? "" : kid));
    }

    @Test
    void disabledContextHasNoSignerBean() {
        runner.run(context -> assertFalse(context.containsBean("remoteBridgePermitSigner")));
    }

    @Test
    void enabledWithValidP256KeyAndKidProducesAWorkingSigner() throws Exception {
        KeyPair kp = ec("secp256r1");
        Path key = pkcs8Pem("p256.pem", kp);
        // slice-3c: the broker also wires now — supply a valid (separate) anchor key so the context boots
        Path anchorKey = pkcs8Pem("p256-anchor.pem", ec("secp256r1"));
        // D step-up: the operator step-up verifier bean also wires now — origin/RP pinning is mandatory
        enabledLoopback(kp, "kid-1", key)
                .withPropertyValues("remote-bridge.recording.anchor-key.path=" + anchorKey,
                        "remote-bridge.step-up.expected-origin=https://operator.acik.com",
                        "remote-bridge.step-up.expected-rp-id=operator.acik.com",
                        // slice-4c-2a: the operator authenticator bean's reference config is mandatory too
                        "remote-bridge.operator-auth.in-memory-token=ref-operator-token-1",
                        "remote-bridge.operator-auth.in-memory-subject=operator@acik.com",
                        "remote-bridge.operator-auth.in-memory-tenant=11111111-1111-1111-1111-111111111111")
                .run(context -> {
            assertNull(context.getStartupFailure());
            RemoteBridgePermitSigner signer = context.getBean(RemoteBridgePermitSigner.class);
            assertNotNull(signer);
            // sign a probe permit and verify it under the matching public key — proves the loaded key works
            OperationPermit unsigned = new OperationPermit(RemoteBridgePermitSigner.PERMIT_ALG, "kid-1",
                    RemoteBridgePermitSigner.PERMIT_VERSION, "policy-1", "sess-1:op-1", "sess-1", "op-1",
                    "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                    CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 1L, null);
            Optional<OperationPermit> signed = signer.sign(unsigned);
            assertTrue(signed.isPresent());
            PublicKey pub = kp.getPublic();
            assertTrue(new RemoteBridgePermitVerifier(pub, "kid-1").verify(signed.get(), 1100L));
        });
    }

    @Test
    void enabledWithoutASigningKeyFailsClosed() throws Exception {
        enabledLoopback(null, "kid-1", null).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context).contains("signing-key-pem-path"),
                    "root was: " + rootMessage(context));
        });
    }

    @Test
    void enabledWithABlankKidFailsClosed() throws Exception {
        KeyPair kp = ec("secp256r1");
        Path key = pkcs8Pem("p256-blankkid.pem", kp);
        enabledLoopback(kp, "", key).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context).contains("kid"), "root was: " + rootMessage(context));
        });
    }

    @Test
    void enabledWithInvalidPemFailsClosed() throws Exception {
        Path garbage = Files.writeString(tempDir.resolve("garbage.pem"), "not a pem");
        runner.withPropertyValues(
                "remote-bridge.enabled=true", "remote-bridge.allow-insecure-plaintext=true",
                "remote-bridge.bind-host=127.0.0.1", "remote-bridge.heartbeat-interval-millis=0",
                "remote-bridge.permit.signing-key-pem-path=" + garbage,
                "remote-bridge.permit.kid=kid-1").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context).contains("failed to load/validate"),
                    "root was: " + rootMessage(context));
        });
    }

    @Test
    void enabledWithASec1KeyIsRefusedPkcs8Only() throws Exception {
        // SEC1 "BEGIN EC PRIVATE KEY" must be refused — PKCS#8 only
        KeyPair kp = ec("secp256r1");
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded());
        Path sec1 = Files.writeString(tempDir.resolve("sec1.pem"),
                "-----BEGIN EC PRIVATE KEY-----\n" + b64 + "\n-----END EC PRIVATE KEY-----\n"); // gitleaks:allow
        enabledLoopback(kp, "kid-1", sec1).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context).contains("failed to load/validate"));
        });
    }

    @Test
    void enabledWithAP384KeyIsRefusedNotP256() throws Exception {
        KeyPair kp = ec("secp384r1");
        Path key = pkcs8Pem("p384.pem", kp);
        enabledLoopback(kp, "kid-1", key).run(context -> {
            assertNotNull(context.getStartupFailure()); // signer ctor's P-256 domain-param compare rejects
            assertTrue(rootMessage(context).contains("failed to load/validate"),
                    "non-P256 must be config-shaped: " + rootMessage(context));
        });
    }

    @Test
    void enabledWithADualArmorPemIsRefused() throws Exception {
        // a concatenated SEC1 + PKCS#8 file must be refused (strict single-block parse, Codex P2)
        KeyPair kp = ec("secp256r1");
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded());
        Path dual = Files.writeString(tempDir.resolve("dual.pem"),
                "-----BEGIN EC PRIVATE KEY-----\n" + b64 + "\n-----END EC PRIVATE KEY-----\n" // gitleaks:allow
                + "-----BEGIN PRIVATE KEY-----\n" + b64 + "\n-----END PRIVATE KEY-----\n");
        enabledLoopback(kp, "kid-1", dual).run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(rootMessage(context).contains("failed to load/validate"),
                    "dual-armor must be refused: " + rootMessage(context));
        });
    }

    /** The full cause-chain messages joined — so a wrap-level message (config-shaped) is matchable. */
    private static String rootMessage(
            org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        StringBuilder sb = new StringBuilder();
        for (Throwable t = context.getStartupFailure(); t != null; t = t.getCause()) {
            if (t.getMessage() != null) {
                sb.append(t.getMessage()).append(" | ");
            }
        }
        return sb.toString();
    }
}
