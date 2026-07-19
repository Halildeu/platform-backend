package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.endpointadmin.remoteaccess.policy.RemoteViewJsonCanonicalizer;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ViewOnlyDsseSignerTest {

    @Test
    void signsCanonicalPayloadWithExactDssePaeAndSingleSignature() {
        RemoteViewJsonCanonicalizer canonicalizer = new RemoteViewJsonCanonicalizer();
        AtomicReference<byte[]> observedPae = new AtomicReference<>();
        ViewOnlyDsseSigner signer = new ViewOnlyDsseSigner(
                canonicalizer,
                pae -> {
                    observedPae.set(pae.clone());
                    return new byte[64];
                },
                "vault-transit://endpoint-admin/view-only-checkpoint#v1");
        JsonNode payload = canonicalizer.mapper().createObjectNode().put("message", "güvenli");

        byte[] envelopeBytes = signer.sign("application/vnd.acik.test+json", payload);
        JsonNode envelope = canonicalizer.strictParse(new String(envelopeBytes, StandardCharsets.UTF_8));

        assertThat(envelope.get("signatures")).hasSize(1);
        assertThat(envelope.at("/signatures/0/keyid").textValue())
                .isEqualTo("vault-transit://endpoint-admin/view-only-checkpoint#v1");
        assertThat(Base64.getDecoder().decode(envelope.at("/signatures/0/sig").textValue())).hasSize(64);
        byte[] canonicalPayload = canonicalizer.canonicalBytes(payload);
        assertThat(Base64.getDecoder().decode(envelope.get("payload").textValue())).isEqualTo(canonicalPayload);
        assertThat(observedPae.get())
                .isEqualTo(ViewOnlyDsseSigner.pae("application/vnd.acik.test+json", canonicalPayload));
    }

    @Test
    void rejectsWrongSignatureLengthWithoutFallback() {
        ViewOnlyDsseSigner signer = new ViewOnlyDsseSigner(
                new RemoteViewJsonCanonicalizer(), pae -> new byte[63],
                "vault-transit://endpoint-admin/view-only-checkpoint#v1");
        assertThatThrownBy(() -> signer.sign(
                "application/vnd.acik.test+json",
                new RemoteViewJsonCanonicalizer().mapper().createObjectNode()))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }
}
