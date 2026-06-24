package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

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

class DirectSttConfigTlsTest {

    @TempDir
    private Path tempDir;

    @Test
    void directSttWebClientPresentsConfiguredClientCertificate() throws Exception {
        final HeldCertificate root = new HeldCertificate.Builder()
                .certificateAuthority(0)
                .commonName("direct-stt-test-root")
                .build();
        final HeldCertificate serverCert = new HeldCertificate.Builder()
                .commonName("live-stt.denetim")
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
                    .setBody("{\"status\":\"ok\"}"));
            server.start();

            final Path caPath = write("ca.crt", root.certificatePem());
            final Path certPath = write("client.crt", clientCert.certificatePem());
            final Path keyPath = write("client.key", clientCert.privateKeyPkcs8Pem());

            final AudioGatewayProperties props = new AudioGatewayProperties();
            props.getDirectStt().setEnabled(true);
            props.getDirectStt().setTranscribeUrl(server.url("/transcribe").toString());
            props.getDirectStt().getTls().setEnabled(true);
            props.getDirectStt().getTls().setCaCertificatePath(caPath.toString());
            props.getDirectStt().getTls().setClientCertificatePath(certPath.toString());
            props.getDirectStt().getTls().setClientPrivateKeyPath(keyPath.toString());
            props.validate();

            final WebClient client = new DirectSttConfig().directSttWebClient(props);
            final String body = client.post()
                    .uri(props.getDirectStt().getTranscribeUrl())
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(5));

            assertThat(body).contains("ok");
            final RecordedRequest request = server.takeRequest();
            assertThat(request.getHandshake()).isNotNull();
            assertThat(request.getHandshake().peerCertificates()).isNotEmpty();
        }
    }

    private Path write(final String name, final String content) throws Exception {
        final Path path = tempDir.resolve(name);
        Files.writeString(path, content);
        return path;
    }
}
