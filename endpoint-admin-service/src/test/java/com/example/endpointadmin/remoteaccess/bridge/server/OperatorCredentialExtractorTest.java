package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.server.OperatorAuthenticator.OperatorCredential;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.cert.X509Certificate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Faz 22.6 slice-4c-2a (Codex 019ebe06) — the extractor pulls the operator credential from the two wire
 * sources (mTLS client-cert chain attribute, Authorization bearer) and is fail-closed: a null request or no
 * credential yields none.
 */
class OperatorCredentialExtractorTest {

    @Test
    void aBearerTokenIsExtracted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer operator-token-xyz");

        OperatorCredential credential = OperatorCredentialExtractor.extract(request);

        assertTrue(credential.bearerToken().isPresent());
        assertEquals("operator-token-xyz", credential.bearerToken().get());
        assertTrue(credential.clientCertChain().isEmpty());
    }

    @Test
    void anMtlsClientCertChainIsExtracted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        X509Certificate leaf = mock(X509Certificate.class);
        request.setAttribute(OperatorCredentialExtractor.X509_ATTRIBUTE, new X509Certificate[] {leaf});

        OperatorCredential credential = OperatorCredentialExtractor.extract(request);

        assertEquals(1, credential.clientCertChain().size());
        assertTrue(credential.bearerToken().isEmpty());
    }

    @Test
    void aRequestWithNoCredentialYieldsNone() {
        OperatorCredential credential = OperatorCredentialExtractor.extract(new MockHttpServletRequest());
        assertTrue(credential.clientCertChain().isEmpty());
        assertTrue(credential.bearerToken().isEmpty());
    }

    @Test
    void aNullRequestYieldsNone() {
        OperatorCredential credential = OperatorCredentialExtractor.extract(null);
        assertTrue(credential.clientCertChain().isEmpty());
        assertTrue(credential.bearerToken().isEmpty());
    }

    @Test
    void aMalformedAuthorizationHeaderYieldsNoBearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Basic dXNlcjpwYXNz"); // not a Bearer
        assertFalse(OperatorCredentialExtractor.extract(request).bearerToken().isPresent());
    }

    @Test
    void aBlankBearerTokenYieldsNoBearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer    ");
        assertFalse(OperatorCredentialExtractor.extract(request).bearerToken().isPresent());
    }
}
