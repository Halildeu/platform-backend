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
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class VaultTransitViewOnlySigningClientTest {

    @Test
    @SuppressWarnings("unchecked")
    void sendsBoundedTransitRequestAndAcceptsOnlyPinnedVersionEd25519Signature() throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        byte[] expected = new byte[64];
        String json = "{\"data\":{\"signature\":\"vault:v1:"
                + Base64.getEncoder().encodeToString(expected) + "\"}}";
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(response);
        char[] token = "hvs.unit-test".toCharArray();
        ViewOnlyVaultTokenSource tokenSource = () -> token;
        VaultTransitViewOnlySigningClient client = new VaultTransitViewOnlySigningClient(
                ViewOnlyAuthorityPropertiesTest.enabledProperties(), tokenSource, http, new ObjectMapper());

        assertThat(client.sign("DSSEv1 payload".getBytes(StandardCharsets.UTF_8))).isEqualTo(expected);

        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(http).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().uri().toString())
                .isEqualTo("https://vault.testai.acik.com/v1/transit/sign/view-only-checkpoint");
        assertThat(request.getValue().headers().firstValue("X-Vault-Token")).contains("hvs.unit-test");
        assertThat(token).containsOnly('\0');
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
                () -> "hvs.unit-test".toCharArray(), http, new ObjectMapper());

        assertThatThrownBy(() -> client.sign(new byte[]{1}))
                .isInstanceOf(ViewOnlyAuthorityException.class)
                .extracting(error -> ((ViewOnlyAuthorityException) error).reason())
                .isEqualTo(ViewOnlyAuthorityError.SIGNING_UNAVAILABLE);
    }
}
