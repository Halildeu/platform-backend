package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.tls.HandshakeCertificates;
import okhttp3.tls.HeldCertificate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Faz 24 İ4 — the meeting-ai {@code /analyze/live} hop must carry the same pinned
 * service identity as the live-stt hop. gitops#2779 reverted the first enable because
 * a plain-HTTP bridge proved nothing about the peer; these tests hold that line.
 */
class LiveAnalyzeMutualTlsTest {

    @TempDir
    private Path tempDir;

    @Test
    void liveAnalyzeWebClientPresentsConfiguredClientCertificate() throws Exception {
        final HeldCertificate root = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName("live-analyze-test-root")
                .build();
        final HeldCertificate serverCert = new HeldCertificate.Builder()
                .commonName("meeting-ai.denetim")
                .addSubjectAlternativeName("localhost")
                .signedBy(root)
                .build();
        final HeldCertificate clientCert = new HeldCertificate.Builder()
                .commonName("audio-gateway-client")
                .signedBy(root)
                .build();

        final HandshakeCertificates serverCertificates = new HandshakeCertificates.Builder()
                .heldCertificate(serverCert, root.certificate())
                .addTrustedCertificate(root.certificate())
                .build();

        try (MockWebServer server = new MockWebServer()) {
            server.useHttps(serverCertificates.sslSocketFactory(), false);
            server.requireClientAuth();
            server.enqueue(new MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"accepted\"}"));
            server.start();

            final AudioGatewayProperties props = tlsProps(root, clientCert);
            props.getDirectStt().getLiveAnalyze().setEnabled(true);
            props.getDirectStt().getLiveAnalyze()
                    .setBaseUrl(server.url("/").toString().replaceAll("/+$", ""));

            final WebClient client = new DirectSttConfig().meetingAiLiveAnalyzeWebClient(props);
            final String body = client.post()
                    .uri("/analyze/live")
                    .bodyValue("{}")
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            assertThat(body).contains("accepted");
            final RecordedRequest request = server.takeRequest();
            assertThat(request.getHandshake()).isNotNull();
            assertThat(request.getHandshake().peerCertificates()).isNotEmpty();
        }
    }

    @Test
    void plaintextBaseUrlIsRefusedWhenMutualTlsIsConfigured() throws Exception {
        final HeldCertificate root = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName("live-analyze-test-root")
                .build();
        final HeldCertificate clientCert = new HeldCertificate.Builder()
                .commonName("audio-gateway-client")
                .signedBy(root)
                .build();

        final AudioGatewayProperties props = tlsProps(root, clientCert);
        props.getDirectStt().getLiveAnalyze().setEnabled(true);
        props.getDirectStt().getLiveAnalyze().setBaseUrl("http://meeting-ai-service:8080");

        assertThatThrownBy(() -> new DirectSttConfig().meetingAiLiveAnalyzeWebClient(props))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be https");
    }

    @Test
    void plaintextBaseUrlStaysAllowedWithoutMutualTls() throws Exception {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().getLiveAnalyze().setEnabled(true);
        props.getDirectStt().getLiveAnalyze().setBaseUrl("http://meeting-ai-service:8080");

        assertThat(new DirectSttConfig().meetingAiLiveAnalyzeWebClient(props)).isNotNull();
        assertThat(props.getDirectStt().getLiveAnalyze().isSecureBaseUrl()).isFalse();
    }

    private AudioGatewayProperties tlsProps(
            final HeldCertificate root, final HeldCertificate clientCert) throws Exception {
        final Path caPath = write("ca.crt", root.certificatePem());
        final Path certPath = write("client.crt", clientCert.certificatePem());
        final Path keyPath = write("client.key", clientCert.privateKeyPkcs8Pem());

        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().getTls().setEnabled(true);
        props.getDirectStt().getTls().setCaCertificatePath(caPath.toString());
        props.getDirectStt().getTls().setClientCertificatePath(certPath.toString());
        props.getDirectStt().getTls().setClientPrivateKeyPath(keyPath.toString());
        return props;
    }

    private Path write(final String name, final String content) throws Exception {
        final Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
