package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Faz 22.6 #548 slice-1 step-5 (Codex {@code 019efada}) — the CANONICAL binding context the device key signs in
 * the TPM-native, broker-nonced challenge-response. Both sides MUST produce byte-identical bytes: the agent
 * (platform-agent, step 6) signs {@code H(context)} with the TPM device key, and the broker
 * ({@link DeviceKeyAttestationRealSessionDeviceTrustVerifier}) RE-DERIVES the same bytes from the broker's own
 * consumed challenge + the authenticated transport, then verifies the signature over THEM (never over the
 * agent-supplied bytes).
 *
 * <p><b>What it binds (Codex):</b> a fresh device-key signature over this context proves the device key holder
 * actively participated in <em>this</em> session — the signature commits to the broker's one-time
 * {@code challengeId} + {@code nonce} (replay/freshness) AND the authenticated {@code transportPeerKey} (so a
 * signature captured on one mTLS session can never be replayed onto another peer's session) AND the challenge
 * {@code expiry} (window). This is the transport-binding the AK quote (bound to the nonce) and the AK certify
 * (AK&rarr;device-key) do not themselves carry.
 *
 * <p><b>Unambiguous encoding:</b> a fixed ASCII domain-separation tag (so these bytes can never collide with any
 * other signed structure), then every variable field is LENGTH-PREFIXED ({@code UINT32} big-endian) before its
 * bytes — so {@code (a,b)} and {@code (a‖b,"")} can never marshal to the same buffer (no concatenation
 * ambiguity). The expiry is a fixed-width {@code UINT64}. {@code challengeId}/{@code transportPeerKey} are ASCII
 * (lowercase hex on the wire); the nonce is its RAW decoded bytes.
 *
 * <p>Pure + deterministic + side-effect-free. The agent reimplements this exact layout in Go (step 6); the
 * format is FROZEN with the #741 wire contract.
 */
public final class DeviceKeySessionBindingContext {

    /** Domain-separation tag (FROZEN). Distinct prefix so these bytes never collide with another signed blob. */
    public static final String DOMAIN_TAG = "F22.6_DEVICE_KEY_SESSION_V1";

    private DeviceKeySessionBindingContext() {
    }

    /**
     * Compute the canonical binding context.
     *
     * @param challengeId           the broker-issued one-time challenge id (lowercase hex)
     * @param nonceRaw              the RAW (decoded) broker nonce bytes
     * @param transportPeerKey      the authenticated mTLS transport peer key (the leaf cert DER SHA-256, lowercase hex)
     * @param expiresAtEpochMillis  the challenge expiry the broker stamped
     * @return the canonical bytes the device key signs; never null
     * @throws IllegalArgumentException if any required input is null/blank/empty (fail-fast — the verifier maps
     *                                  this to a fail-closed deny, never a partial context)
     */
    public static byte[] compute(String challengeId, byte[] nonceRaw, String transportPeerKey,
                                 long expiresAtEpochMillis) {
        if (challengeId == null || challengeId.isBlank()) {
            throw new IllegalArgumentException("challengeId required for binding context");
        }
        if (nonceRaw == null || nonceRaw.length == 0) {
            throw new IllegalArgumentException("nonce required for binding context");
        }
        if (transportPeerKey == null || transportPeerKey.isBlank()) {
            throw new IllegalArgumentException("transportPeerKey required for binding context");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            out.write(DOMAIN_TAG.getBytes(StandardCharsets.US_ASCII)); // fixed-length tag, no prefix needed
            out.writeByte(0); // NUL terminator separates the fixed tag from the first length-prefixed field
            writeLengthPrefixed(out, challengeId.getBytes(StandardCharsets.US_ASCII));
            writeLengthPrefixed(out, nonceRaw);
            writeLengthPrefixed(out, transportPeerKey.getBytes(StandardCharsets.US_ASCII));
            out.writeLong(expiresAtEpochMillis); // fixed-width UINT64, big-endian
        } catch (IOException impossibleOnByteArray) {
            // a ByteArrayOutputStream never throws IOException — surface defensively, never silently
            throw new UncheckedIOException("binding-context assembly failed", impossibleOnByteArray);
        }
        return buffer.toByteArray();
    }

    private static void writeLengthPrefixed(DataOutputStream out, byte[] field) throws IOException {
        out.writeInt(field.length); // UINT32 big-endian length prefix → no concatenation ambiguity
        out.write(field);
    }
}
