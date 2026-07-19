package com.example.endpointadmin.remoteaccess.preflight;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VaultTransitViewOnlySigningClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void readinessProbeRequiresMetadataSignAclAndRuntimeRootVerifiableSignature() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = Arrays.copyOfRange(
                pair.getPublic().getEncoded(), pair.getPublic().getEncoded().length - 32,
                pair.getPublic().getEncoded().length);
        byte[] readinessPae = ViewOnlyDsseSigner.pae(
                "application/vnd.acik.faz22-6-view-only-signing-readiness.v1+json",
                "{}".getBytes(StandardCharsets.UTF_8));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(readinessPae);
        byte[] readinessSignature = signer.sign();

        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> metadataResponse = mock(HttpResponse.class);
        HttpResponse<InputStream> signingResponse = mock(HttpResponse.class);
        String json = "{\"data\":{\"type\":\"ed25519\",\"supports_signing\":true,"
                + "\"latest_version\":1,\"min_available_version\":1,\"min_encryption_version\":1,"
                + "\"keys\":{\"1\":{\"public_key\":"
                + "\"" + Base64.getEncoder().encodeToString(publicKey) + "\"}}}}";
        String signed = "{\"data\":{\"signature\":\"vault:v1:"
                + Base64.getEncoder().encodeToString(readinessSignature) + "\"}}";
        when(metadataResponse.statusCode()).thenReturn(200);
        when(metadataResponse.body()).thenReturn(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(signingResponse.statusCode()).thenReturn(200);
        when(signingResponse.body()).thenReturn(
                new ByteArrayInputStream(signed.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(metadataResponse, signingResponse);
        ViewOnlyAuthorityProperties properties = ViewOnlyAuthorityPropertiesTest.enabledProperties();
        properties.setVaultTransitPublicKeySha256(fingerprint(publicKey));
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                properties, () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper(), publicKey);

        client.probeReady();

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http, org.mockito.Mockito.times(2))
                .send(request.capture(), any(HttpResponse.BodyHandler.class));
        List<HttpRequest> requests = request.getAllValues();
        assertThat(requests.get(0).method()).isEqualTo("GET");
        assertThat(requests.get(0).uri().toString())
                .isEqualTo("https://vault.testai.acik.com/v1/endpoint-admin/keys/view-only-checkpoint");
        assertThat(requests.get(1).method()).isEqualTo("POST");
        assertThat(requests.get(1).uri().toString())
                .isEqualTo("https://vault.testai.acik.com/v1/endpoint-admin/sign/view-only-checkpoint");
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessProbeRejectsMissingPinnedPublicKey() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String json = "{\"data\":{\"type\":\"ed25519\",\"keys\":{\"1\":{}}}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                ViewOnlyAuthorityPropertiesTest.enabledProperties(),
                () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper(), new byte[32]);

        assertThatThrownBy(client::probeReady)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void readinessProbeRejectsWrongPublicKeyFingerprintAndUnsignableVersion() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String json = "{\"data\":{\"type\":\"ed25519\",\"supports_signing\":false,"
                + "\"latest_version\":1,\"keys\":{\"1\":{\"public_key\":"
                + "\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=\"}}}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                ViewOnlyAuthorityPropertiesTest.enabledProperties(),
                () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper(), new byte[32]);

        assertThatThrownBy(client::probeReady)
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsBoundedTransitRequestAndAcceptsOnlyPinnedVersionEd25519Signature() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = Arrays.copyOfRange(
                pair.getPublic().getEncoded(), pair.getPublic().getEncoded().length - 32,
                pair.getPublic().getEncoded().length);
        byte[] pae = "DSSEv1 payload".getBytes(StandardCharsets.UTF_8);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(pair.getPrivate());
        signer.update(pae);
        byte[] expected = signer.sign();
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String json = "{\"data\":{\"signature\":\"vault:v1:"
                + Base64.getEncoder().encodeToString(expected) + "\"}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        char[] token = "hvs.unit-test".toCharArray();
        ViewOnlyVaultTokenSource tokenSource = () -> token;
        ViewOnlyAuthorityProperties properties = ViewOnlyAuthorityPropertiesTest.enabledProperties();
        properties.setVaultTransitPublicKeySha256(fingerprint(publicKey));
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                properties, tokenSource, http, new ObjectMapper(), publicKey);

        assertThat(client.sign(pae)).isEqualTo(expected);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://vault.testai.acik.com/v1/endpoint-admin/sign/view-only-checkpoint");
        assertThat(request.getValue().headers().firstValue("X-Vault-Token")).contains("hvs.unit-test");
        assertThat(token).containsOnly('\0');
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsPinnedVersionSignatureThatDoesNotVerifyAgainstRuntimeRoot() throws Exception {
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] publicKey = Arrays.copyOfRange(
                pair.getPublic().getEncoded(), pair.getPublic().getEncoded().length - 32,
                pair.getPublic().getEncoded().length);
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String json = "{\"data\":{\"signature\":\"vault:v1:"
                + Base64.getEncoder().encodeToString(new byte[64]) + "\"}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        ViewOnlyAuthorityProperties properties = ViewOnlyAuthorityPropertiesTest.enabledProperties();
        properties.setVaultTransitPublicKeySha256(fingerprint(publicKey));
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                properties, () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper(), publicKey);

        assertThatThrownBy(() -> client.sign("DSSEv1 payload".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }

    @Test
    @SuppressWarnings("unchecked")
    void rejectsWrongVersionAndMapsFailureOnlyToSigningUnavailable() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        String json = "{\"data\":{\"signature\":\"vault:v2:"
                + Base64.getEncoder().encodeToString(new byte[64]) + "\"}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                ViewOnlyAuthorityPropertiesTest.enabledProperties(),
                () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper(), new byte[32]);

        assertThatThrownBy(() -> client.sign(new byte[]{1}))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }

    private static String fingerprint(byte[] value) throws Exception {
        return "sha256:" + java.util.HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(value));
    }
}
