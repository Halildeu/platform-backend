package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AudioGatewayPropertiesTest {

    @TempDir
    private Path tempDir;

    @Test
    void directSttHttpWithoutTlsRemainsValidForLocalAndMockServerPaths() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void directSttTlsRequiresHttpsTranscribeUrl() throws IOException {
        final AudioGatewayProperties props = directSttProps("http://live-stt.denetim:8243/transcribe");
        enableTlsWithReadableFiles(props);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tls.enabled=true requires an https transcribe-url");
    }

    @Test
    void directSttTlsRequiresReadableCaCertAndClientMaterial() {
        final AudioGatewayProperties props = directSttProps("https://live-stt.denetim:8243/transcribe");
        props.getDirectStt().getTls().setEnabled(true);
        props.getDirectStt().getTls().setCaCertificatePath(tempDir.resolve("missing-ca.crt").toString());
        props.getDirectStt().getTls().setClientCertificatePath(tempDir.resolve("missing-client.crt").toString());
        props.getDirectStt().getTls().setClientPrivateKeyPath(tempDir.resolve("missing-client.key").toString());

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ca-certificate-path must point to a readable file");
    }

    @Test
    void directSttTlsAcceptsReadablePemPaths() throws IOException {
        final AudioGatewayProperties props = directSttProps("https://live-stt.denetim:8243/transcribe");
        enableTlsWithReadableFiles(props);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void directSttTranscriptResultStreamRequiresStreamKeyWhenEnabled() {
        final AudioGatewayProperties props = directSttProps("https://live-stt.denetim:8243/transcribe");
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        props.getDirectStt().getTranscriptResultStream().setStreamKey(" ");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transcript-result-stream.stream-key must be set");
    }

    @Test
    void directSttTranscriptResultStreamRejectsNegativeMaxLen() {
        final AudioGatewayProperties props = directSttProps("https://live-stt.denetim:8243/transcribe");
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
        props.getDirectStt().getTranscriptResultStream().setMaxLen(-1);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transcript-result-stream.max-len must be >= 0");
    }

    private static AudioGatewayProperties directSttProps(final String transcribeUrl) {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().setEnabled(true);
        props.getDirectStt().setTranscribeUrl(transcribeUrl);
        return props;
    }

    private void enableTlsWithReadableFiles(final AudioGatewayProperties props) throws IOException {
        final Path ca = writePlaceholder("ca.crt");
        final Path cert = writePlaceholder("client.crt");
        final Path key = writePlaceholder("client.key");
        props.getDirectStt().getTls().setEnabled(true);
        props.getDirectStt().getTls().setCaCertificatePath(ca.toString());
        props.getDirectStt().getTls().setClientCertificatePath(cert.toString());
        props.getDirectStt().getTls().setClientPrivateKeyPath(key.toString());
    }

    private Path writePlaceholder(final String name) throws IOException {
        final Path path = tempDir.resolve(name);
        Files.writeString(path, "placeholder");
        return path;
    }
}
