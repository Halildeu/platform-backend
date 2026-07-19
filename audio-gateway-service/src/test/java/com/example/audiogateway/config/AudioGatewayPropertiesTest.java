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
    void defaultBoundsPassValidation() {
        assertThatCode(new AudioGatewayProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void boundsRejectZeroMaxBufferedSeconds() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxBufferedSeconds(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-buffered-seconds must be positive");
    }

    @Test
    void boundsRejectNegativeMaxBufferedSeconds() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxBufferedSeconds(-5);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-buffered-seconds must be positive");
    }

    @Test
    void boundsValidationLeavesMaxSessionMinutesZeroSupported() {
        // max-session-minutes=0 is a supported degenerate value (session expires at
        // creation time — SessionExpiryContractTest relies on it), so bounds
        // validation must NOT reject it; only maxBufferedSeconds is guarded (#428).
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxSessionMinutes(0);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void boundsRejectNegativeMaxSessionMinutes() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setMaxSessionMinutes(-1);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-session-minutes must be non-negative");
    }

    @Test
    void boundsRejectNonPositiveSessionExpirySweep() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getBounds().setSessionExpirySweepMs(0L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("session-expiry-sweep-ms must be positive");
    }

    @Test
    void auditRejectsAnyProducerSideStreamTrim() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getAudit().getRedis().setMaxLen(1L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit.redis.max-len must be 0");
    }

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

    @Test
    void directSttTranscriptResultStreamRejectsNonPositiveAttemptTimeout() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getTranscriptResultStream().setDeliveryAttemptTimeoutMs(0L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("delivery-attempt-timeout-ms must be in [1,30000]");
    }

    @Test
    void directSttTranscriptResultStreamRejectsTotalTimeoutBelowAttemptTimeout() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getTranscriptResultStream().setDeliveryAttemptTimeoutMs(2_000L);
        props.getDirectStt().getTranscriptResultStream().setDeliveryTotalTimeoutMs(1_999L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "delivery-total-timeout-ms must be in [delivery-attempt-timeout-ms,60000]");
    }

    @Test
    void directSttAggregationRejectsWindowOutsideFiveToThirtySeconds() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getAggregation().setWindowSeconds(4);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregation.window-seconds must be in [5,30]");
    }

    @Test
    void directSttAggregationRequiresPositiveSessionBound() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getAggregation().setMaxBufferedSessions(0);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("aggregation.max-buffered-sessions must be positive");
    }

    @Test
    void liveStreamingAcceptsWsUrlAndDefaultBounds() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);

        assertThatCode(props::validate).doesNotThrowAnyException();
    }

    @Test
    void liveStreamingRequiresDurableTranscriptResultStream() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getTranscriptResultStream().setEnabled(false);
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("transcript-result-stream.enabled must be true");
    }

    @Test
    void liveStreamingRequiresDirectSttToBeEnabled() {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct-stt.enabled must be true when streaming is enabled");
    }

    @Test
    void liveStreamingRequiresWssWhenDirectSttTlsIsEnabled() throws IOException {
        final AudioGatewayProperties props =
                directSttProps("https://live-stt.denetim:8243/transcribe");
        enableTlsWithReadableFiles(props);
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://live-stt.denetim:8243/ws/stream");

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("direct-stt TLS requires a wss streaming URL");
    }

    @Test
    void liveStreamingRejectsFrameSizeOutsideUnsignedShortRange() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getStreaming().setMaxFrameBytes(65_536);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("streaming.max-frame-bytes must be in [1,65535]");
    }

    @Test
    void liveStreamingRejectsUnboundedTerminalControlSize() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getStreaming().setMaxTerminalControlBytes(1_025);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-terminal-control-bytes must be in [14,1024]");
    }

    @Test
    void liveStreamingRejectsReadyTimeoutAboveColdLoadBound() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getStreaming().setReadyTimeoutMs(600_001L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ready-timeout-ms must be in [1,600000]");
    }

    @Test
    void liveStreamingRejectsTerminalDrainTimeoutAboveFinalPersistenceBudget() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getStreaming().setTerminalDrainTimeoutMs(180_001L);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal-drain-timeout-ms must be in [1,180000]");
    }

    @Test
    void liveStreamingRequiresSourceHistoryToHoldAtLeastOneMaximumFrame() {
        final AudioGatewayProperties props = directSttProps("http://localhost:8200/transcribe");
        props.getDirectStt().getStreaming().setEnabled(true);
        props.getDirectStt().getStreaming().setStreamUrl("ws://localhost:8200/ws/stream");
        props.getDirectStt().getStreaming().setMaxFrameBytes(1_024);
        props.getDirectStt().getStreaming().setSourceHistoryMaxBytes(1_023);

        assertThatThrownBy(props::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "source-history-max-bytes must be in [max-frame-bytes,16777216]");
    }

    private static AudioGatewayProperties directSttProps(final String transcribeUrl) {
        final AudioGatewayProperties props = new AudioGatewayProperties();
        props.getDirectStt().setEnabled(true);
        props.getDirectStt().setTranscribeUrl(transcribeUrl);
        props.getDirectStt().getTranscriptResultStream().setEnabled(true);
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
