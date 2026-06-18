package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.security.TestX509Certs;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLPeerUnverifiedException;
import javax.net.ssl.SSLSession;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PeerIdentityInterceptorTest {

    @Test
    void fromSslSessionExtractsTheAdComputerSanBindingFromTheLeafCertificate() throws Exception {
        UUID objectGuid = UUID.fromString("44444444-4444-4444-4444-444444444444");
        X509Certificate certificate = TestX509Certs.validClientCert(objectGuid);
        SSLSession session = sslSession(certificate);

        PeerIdentity identity = PeerIdentityInterceptor.fromSslSession(session);

        assertEquals(PeerIdentityInterceptor.fingerprint(certificate), identity.transportPeerKey());
        assertEquals(objectGuid.toString(), identity.certBoundAdComputerId().orElseThrow());
        assertTrue(identity.certBoundDeviceId().isEmpty(), "endpoint device id is not parsed at transport layer");
        assertEquals(certificate, identity.chain().get(0));
    }

    @Test
    void fromSslSessionDoesNotClaimAnAdComputerBindingWhenTheSanIsMissing() throws Exception {
        X509Certificate certificate = TestX509Certs.builder()
                .includeSanUri(false)
                .build();
        SSLSession session = sslSession(certificate);

        PeerIdentity identity = PeerIdentityInterceptor.fromSslSession(session);

        assertTrue(identity.certBoundAdComputerId().isEmpty());
    }

    @Test
    void fromSslSessionDoesNotClaimAnAdComputerBindingWhenTheSanIsAmbiguous() throws Exception {
        UUID first = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID second = UUID.fromString("55555555-5555-5555-5555-555555555555");
        X509Certificate certificate = TestX509Certs.builder()
                .objectGuid(first)
                .extraSanUri("adcomputer:" + second)
                .build();
        SSLSession session = sslSession(certificate);

        PeerIdentity identity = PeerIdentityInterceptor.fromSslSession(session);

        assertTrue(identity.certBoundAdComputerId().isEmpty());
    }

    @Test
    void fromSslSessionReturnsNullForAnUnverifiedPeer() throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenThrow(new SSLPeerUnverifiedException("anonymous"));

        assertNull(PeerIdentityInterceptor.fromSslSession(session));
    }

    private static SSLSession sslSession(X509Certificate certificate) throws Exception {
        SSLSession session = mock(SSLSession.class);
        when(session.getPeerCertificates()).thenReturn(new Certificate[]{certificate});
        return session;
    }
}
