package com.example.endpointadmin.remoteaccess.preflight;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;

/** Canonical single-signature JSON DSSE envelope builder using external Transit signing. */
public final class ViewOnlyDsseSigner {
    private static final Pattern KEY_ID = Pattern.compile("vault-transit://[a-z0-9/_-]+#v[1-9][0-9]*");
    private static final int MAX_PAYLOAD_TYPE_BYTES = 200;
    private static final int MAX_BASE64_PAYLOAD_BYTES = 262_144;

    private final RemoteViewJsonCanonicalizer canonicalizer;
    private final ViewOnlyTransitSigningClient transit;
    private final String keyId;

    public ViewOnlyDsseSigner(RemoteViewJsonCanonicalizer canonicalizer,
                              ViewOnlyTransitSigningClient transit,
                              String keyId) {
        this.canonicalizer = canonicalizer;
        this.transit = transit;
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) {
            throw new IllegalArgumentException("canonical Vault Transit key ID is required");
        }
        this.keyId = keyId;
    }

    public byte[] sign(String payloadType, JsonNode payload) {
        if (payloadType == null || payloadType.isBlank()
                || payloadType.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_TYPE_BYTES) {
            throw signerFailure("DSSE payload type is empty or exceeds its hard bound", null);
        }
        byte[] canonicalPayload = canonicalizer.canonicalBytes(payload);
        String encodedPayload = Base64.getEncoder().encodeToString(canonicalPayload);
        if (encodedPayload.length() > MAX_BASE64_PAYLOAD_BYTES) {
            throw signerFailure("DSSE payload exceeds its hard base64 bound", null);
        }
        byte[] signature;
        try {
            signature = transit.sign(pae(payloadType, canonicalPayload));
        } catch (RuntimeException transitFailure) {
            throw signerFailure("Vault Transit signing failed closed", transitFailure);
        }
        if (signature == null || signature.length != 64) {
            throw signerFailure("Vault Transit did not return one 64-byte Ed25519 signature", null);
        }

        ObjectNode envelope = canonicalizer.mapper().createObjectNode();
        envelope.put("payloadType", payloadType);
        envelope.put("payload", encodedPayload);
        ArrayNode signatures = envelope.putArray("signatures");
        ObjectNode entry = signatures.addObject();
        entry.put("keyid", keyId);
        entry.put("sig", Base64.getEncoder().encodeToString(signature));
        return canonicalizer.canonicalBytes(envelope);
    }

    static byte[] pae(String payloadType, byte[] payload) {
        byte[] type = payloadType.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, "DSSEv1 ");
        write(out, Integer.toString(type.length));
        write(out, " ");
        out.writeBytes(type);
        write(out, " ");
        write(out, Integer.toString(payload.length));
        write(out, " ");
        out.writeBytes(payload);
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String value) {
        out.writeBytes(value.getBytes(StandardCharsets.US_ASCII));
    }

    private static ViewOnlyAuthorityException signerFailure(String message, Throwable cause) {
        return new ViewOnlyAuthorityException(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE, message, cause);
    }
}
