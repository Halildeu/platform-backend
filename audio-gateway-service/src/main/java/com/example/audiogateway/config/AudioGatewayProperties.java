package com.example.audiogateway.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code audio.gateway.*} properties.
 *
 * <p>Codex {@code 019e879c} + {@code 019e8c26} iter-2 AGREE: bounds + JWT claims +
 * idempotency policy configurable; hard-coded YASAK. Defaults reflect ADR-0031 PoC
 * scope (256 KB / 60 dk / 1000 active sessions / 4096 replay cache).
 *
 * <p>Registered via {@code @ConfigurationPropertiesScan} on {@code AudioGatewayApplication}.
 */
@ConfigurationProperties(prefix = "audio.gateway")
public class AudioGatewayProperties {

    /**
     * Codex {@code 019e8df2} iter-4 P1.2 absorb: nested {@code @PostConstruct} bean lifecycle
     * callback olarak çağrılmaz; outer {@code AudioGatewayProperties} bean üzerinde tetikle.
     */
    @jakarta.annotation.PostConstruct
    public void validate() {
        bounds.validate();
        dispatcher.validate();
        audit.validate();
        directStt.validate();
        meetingAccess.validate();
    }


    private final Contract contract = new Contract();
    private final Bounds bounds = new Bounds();
    private final Dispatcher dispatcher = new Dispatcher();
    private final Jwt jwt = new Jwt();
    private final Idempotency idempotency = new Idempotency();
    private final Audit audit = new Audit();
    private final DirectStt directStt = new DirectStt();
    private final MeetingAccess meetingAccess = new MeetingAccess();

    public Contract getContract() {
        return contract;
    }

    public Bounds getBounds() {
        return bounds;
    }

    public Dispatcher getDispatcher() {
        return dispatcher;
    }

    public Jwt getJwt() {
        return jwt;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public Audit getAudit() {
        return audit;
    }

    public DirectStt getDirectStt() {
        return directStt;
    }

    public MeetingAccess getMeetingAccess() {
        return meetingAccess;
    }

    public static class Contract {
        private String version = "1.0";

        public String getVersion() {
            return version;
        }

        public void setVersion(final String version) {
            this.version = version;
        }
    }

    public static class Bounds {
        private long maxChunkBytes = 262_144L;
        private int maxBufferedSeconds = 30;
        private int maxSessionMinutes = 60;
        private long sessionExpirySweepMs = 30_000L;
        private int admissionQueueCapacity = 1_000;
        private int maxActiveSessions = 1_000;

        /**
         * Fail-closed startup validation for {@code maxBufferedSeconds} — the subject
         * of the #428 owner decision (acceptance #6). It is the server-observed
         * undelivered-audio backlog bound; an invalid / negative / zero value is a
         * misconfiguration, not a "disabled" signal, so it fails startup rather than
         * silently bounding nothing.
         *
         * <p>{@code maxSessionMinutes=0} is a supported (degenerate) value — sessions
         * expire at creation time, exercised by {@code SessionExpiryContractTest}. A
         * negative duration and a non-positive sweep cadence are invalid. Session duration,
         * buffered audio and chunk size remain independent bounds.
         */
        public void validate() {
            if (maxBufferedSeconds <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.bounds.max-buffered-seconds must be positive, got "
                                + maxBufferedSeconds);
            }
            if (maxSessionMinutes < 0) {
                throw new IllegalStateException(
                        "audio.gateway.bounds.max-session-minutes must be non-negative, got "
                                + maxSessionMinutes);
            }
            if (sessionExpirySweepMs <= 0L) {
                throw new IllegalStateException(
                        "audio.gateway.bounds.session-expiry-sweep-ms must be positive, got "
                                + sessionExpirySweepMs);
            }
        }

        public long getMaxChunkBytes() {
            return maxChunkBytes;
        }

        public void setMaxChunkBytes(final long maxChunkBytes) {
            this.maxChunkBytes = maxChunkBytes;
        }

        public int getMaxBufferedSeconds() {
            return maxBufferedSeconds;
        }

        public void setMaxBufferedSeconds(final int maxBufferedSeconds) {
            this.maxBufferedSeconds = maxBufferedSeconds;
        }

        public int getMaxSessionMinutes() {
            return maxSessionMinutes;
        }

        public void setMaxSessionMinutes(final int maxSessionMinutes) {
            this.maxSessionMinutes = maxSessionMinutes;
        }

        public long getSessionExpirySweepMs() {
            return sessionExpirySweepMs;
        }

        public void setSessionExpirySweepMs(final long sessionExpirySweepMs) {
            this.sessionExpirySweepMs = sessionExpirySweepMs;
        }

        public int getAdmissionQueueCapacity() {
            return admissionQueueCapacity;
        }

        public void setAdmissionQueueCapacity(final int admissionQueueCapacity) {
            this.admissionQueueCapacity = admissionQueueCapacity;
        }

        public int getMaxActiveSessions() {
            return maxActiveSessions;
        }

        public void setMaxActiveSessions(final int maxActiveSessions) {
            this.maxActiveSessions = maxActiveSessions;
        }
    }

    /**
     * Dispatcher config — Codex {@code 019e8df2} iter-2 AGREE PR-gw-01B3:
     * {@code audio.gateway.dispatcher.mode} canonical (eski {@code audio.gateway.stt.dispatch-mode}
     * retire); {@code mode=noop} default; {@code mode=redis} PR-gw-01C scope (yeni
     * RedisStreamsAudioChunkDispatcher bean register).
     */
    public static class Dispatcher {
        /**
         * Supported modes. PR-gw-01B3 shipped {@code noop}; PR-gw-01C (#106) adds
         * {@code redis} — the cross-server Redis Streams producer.
         */
        private static final java.util.Set<String> SUPPORTED_MODES = java.util.Set.of("noop", "redis");

        private String mode = "noop";
        private long queueFullRetryAfterSeconds = 5L;
        private long unavailableRetryAfterSeconds = 30L;

        // PR-gw-01C (#106) Redis Streams producer config — no hard-coded values.
        // Partition-based keys (Codex 019e97bb iter-1 absorb + ADR-0031 D3):
        // audio:chunks:p00..p31, key = prefix + hash(tenantId+sessionId) % partitionCount.
        // Per-tenant keys were rejected: 100+ tenants explode the Redis keyspace and
        // defeat consumer-group horizontal scale.
        private String streamKeyPrefix = "audio:chunks:p";
        private int partitionCount = 32;
        private long streamMaxLen = 10_000L;

        // P2-1 (review iter-2): Codex 8-scenario 429/503 + Retry-After enumeration.
        // Consumer-lag gate is producer-side XPENDING introspection on the
        // live-stt-v1 consumer group (ADR-0031 D3); transient cluster failover
        // gets a short retry (5s) vs hard outage (30s).
        private String consumerGroup = "live-stt-v1";
        private long consumerLagPendingThreshold = 10_000L;
        private long consumerLagRetryAfterSeconds = 10L;
        private long consumerIdleThresholdMs = 60_000L;
        private long failoverRetryAfterSeconds = 5L;

        public String getMode() {
            return mode;
        }

        public void setMode(final String mode) {
            this.mode = mode;
        }

        /**
         * Codex {@code 019e8df2} iter-3+iter-4 P1.2 absorb: fail-fast unsupported mode.
         * Outer {@link AudioGatewayProperties#validate()} tetikler (nested
         * {@code @PostConstruct} bean lifecycle olarak çağrılmaz).
         */
        public void validate() {
            if (!SUPPORTED_MODES.contains(mode)) {
                throw new IllegalStateException(
                        "audio.gateway.dispatcher.mode='" + mode + "' not supported — "
                        + "supported modes: " + SUPPORTED_MODES);
            }
            if (streamMaxLen <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.dispatcher.stream-max-len must be positive, got " + streamMaxLen);
            }
            // %02d key suffix formatting caps the usable partition range at 100.
            if (partitionCount < 1 || partitionCount > 100) {
                throw new IllegalStateException(
                        "audio.gateway.dispatcher.partition-count must be in [1,100], got " + partitionCount);
            }
            if (consumerLagPendingThreshold <= 0 || consumerIdleThresholdMs <= 0
                    || consumerLagRetryAfterSeconds <= 0 || failoverRetryAfterSeconds <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.dispatcher consumer-lag/failover thresholds must be positive");
            }
        }

        public int getPartitionCount() {
            return partitionCount;
        }

        public void setPartitionCount(final int partitionCount) {
            this.partitionCount = partitionCount;
        }

        public String getStreamKeyPrefix() {
            return streamKeyPrefix;
        }

        public void setStreamKeyPrefix(final String streamKeyPrefix) {
            this.streamKeyPrefix = streamKeyPrefix;
        }

        public long getStreamMaxLen() {
            return streamMaxLen;
        }

        public void setStreamMaxLen(final long streamMaxLen) {
            this.streamMaxLen = streamMaxLen;
        }

        public long getQueueFullRetryAfterSeconds() {
            return queueFullRetryAfterSeconds;
        }

        public void setQueueFullRetryAfterSeconds(final long queueFullRetryAfterSeconds) {
            this.queueFullRetryAfterSeconds = queueFullRetryAfterSeconds;
        }

        public long getUnavailableRetryAfterSeconds() {
            return unavailableRetryAfterSeconds;
        }

        public void setUnavailableRetryAfterSeconds(final long unavailableRetryAfterSeconds) {
            this.unavailableRetryAfterSeconds = unavailableRetryAfterSeconds;
        }

        public String getConsumerGroup() {
            return consumerGroup;
        }

        public void setConsumerGroup(final String consumerGroup) {
            this.consumerGroup = consumerGroup;
        }

        public long getConsumerLagPendingThreshold() {
            return consumerLagPendingThreshold;
        }

        public void setConsumerLagPendingThreshold(final long consumerLagPendingThreshold) {
            this.consumerLagPendingThreshold = consumerLagPendingThreshold;
        }

        public long getConsumerLagRetryAfterSeconds() {
            return consumerLagRetryAfterSeconds;
        }

        public void setConsumerLagRetryAfterSeconds(final long consumerLagRetryAfterSeconds) {
            this.consumerLagRetryAfterSeconds = consumerLagRetryAfterSeconds;
        }

        public long getConsumerIdleThresholdMs() {
            return consumerIdleThresholdMs;
        }

        public void setConsumerIdleThresholdMs(final long consumerIdleThresholdMs) {
            this.consumerIdleThresholdMs = consumerIdleThresholdMs;
        }

        public long getFailoverRetryAfterSeconds() {
            return failoverRetryAfterSeconds;
        }

        public void setFailoverRetryAfterSeconds(final long failoverRetryAfterSeconds) {
            this.failoverRetryAfterSeconds = failoverRetryAfterSeconds;
        }
    }

    /**
     * Direct-STT forwarding config — Faz 24 issue #182 (architecture "A":
     * direct HTTP POST gateway→live-stt {@code /transcribe}; Redis stays
     * metadata/coordination-only; raw audio NEVER persisted). Cross-AI decision
     * Codex {@code 019eeb45} + Claude AGREE; implementation contract hardened by
     * Codex {@code 019eeb5f} REVISE absorb (bounded in-flight + hard async boundary
     * + strict config validation).
     *
     * <p>DEFAULT-OFF: when {@code enabled=false} (default) the
     * {@link com.example.audiogateway.service.DirectSttForwardingDispatcher} bean is
     * NOT registered and live behaviour is unchanged (mirrors the dispatcher
     * {@code mode=noop|redis} discipline). When {@code enabled=true} the decorator
     * wraps whichever base dispatcher the {@code dispatcher.mode} selects and adds a
     * best-effort, bounded, fire-and-forget audio forward to {@code transcribe-url}.
     */
    public static class DirectStt {
        /** DEFAULT-OFF — see {@link DirectStt}. */
        private boolean enabled = false;

        /**
         * live-stt {@code /transcribe} absolute URL (e.g.
         * {@code https://10.99.0.2:8000/transcribe} over the WireGuard+mTLS tunnel,
         * ADR-0031 §D2). Required + must be http/https when {@code enabled=true};
         * validated fail-closed at startup. Never logged in full (may carry sensitive
         * query material).
         */
        private String transcribeUrl = "";

        /**
         * Max concurrent in-flight direct-STT forwards (Codex {@code 019eeb5f} REVISE
         * point 3 — bounded heap-of-raw-audio + outbound concurrency). Acquired
         * non-blocking in the admission monitor; on saturation the forward is DROPPED
         * (best-effort), the chunk admission outcome is unaffected, and the
         * {@code dropped_saturation} metric is incremented.
         */
        private int maxInFlight = 32;

        /** WebClient TCP connect timeout (ms). */
        private long connectTimeoutMs = 3_000L;

        /**
         * WebClient response timeout (ms) — GPU {@code /transcribe} can take hundreds
         * of ms to seconds; this caps how long a forward holds an in-flight permit.
         */
        private long responseTimeoutMs = 30_000L;

        /** Max in-memory bytes for decoding the (small) {@code /transcribe} JSON response. */
        private int maxResponseBytes = 262_144;

        /**
         * Memory-only PCM16 window aggregation before the live-stt hop (Faz 24 #231).
         * Direct-STT itself is default-off; once enabled, aggregation is the safe default
         * because forwarding every recorder chunk cannot keep pace with Whisper inference.
         */
        private final Aggregation aggregation = new Aggregation();

        /**
         * App-layer TLS / mTLS settings for the direct-STT hop. DEFAULT-OFF so existing
         * local/test HTTP MockWebServer paths stay unchanged; real cross-server meeting
         * audio enables this per ADR-0031 + B+ I7.
         */
        private final Tls tls = new Tls();

        /**
         * Durable transcript-result stream handoff. This is transcript content, not raw
         * audio. It may remain off while direct-STT is off, but startup fails closed when
         * direct-STT is enabled without this handoff.
         */
        private final TranscriptResultStream transcriptResultStream = new TranscriptResultStream();

        /** Default-off gateway-to-live-stt WebSocket bridge for Faz 24 #184. */
        private final Streaming streaming = new Streaming();

        /**
         * Fail-closed validation (Codex {@code 019eeb5f} REVISE point 10): when enabled,
         * a missing/blank/non-http(s) {@code transcribe-url} or a non-positive bound is a
         * startup error — silent fallback that hides misconfiguration is YASAK.
         */
        public void validate() {
            if (!enabled) {
                if (streaming.isEnabled()) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.enabled must be true when streaming is enabled");
                }
                return;
            }
            if (transcribeUrl == null || transcribeUrl.isBlank()) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.transcribe-url must be set when direct-stt is enabled");
            }
            final java.net.URI uri;
            try {
                uri = java.net.URI.create(transcribeUrl.trim());
            } catch (final IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.transcribe-url is not a valid URI", ex);
            }
            final String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.transcribe-url must be http or https, got scheme="
                                + scheme);
            }
            if (tls.isEnabled() && !"https".equalsIgnoreCase(scheme)) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.tls.enabled=true requires an https transcribe-url");
            }
            tls.validate();
            if (maxInFlight <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.max-in-flight must be positive, got " + maxInFlight);
            }
            if (connectTimeoutMs <= 0 || responseTimeoutMs <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt connect/response timeouts must be positive");
            }
            if (maxResponseBytes <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.max-response-bytes must be positive, got "
                                + maxResponseBytes);
            }
            aggregation.validate();
            transcriptResultStream.validate();
            streaming.validate(tls.isEnabled());
            if (!transcriptResultStream.isEnabled()) {
                throw new IllegalStateException(
                        "audio.gateway.direct-stt.transcript-result-stream.enabled must be true "
                                + "when direct-stt is enabled");
            }
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(final boolean enabled) {
            this.enabled = enabled;
        }

        public String getTranscribeUrl() {
            return transcribeUrl;
        }

        public void setTranscribeUrl(final String transcribeUrl) {
            this.transcribeUrl = transcribeUrl;
        }

        public int getMaxInFlight() {
            return maxInFlight;
        }

        public void setMaxInFlight(final int maxInFlight) {
            this.maxInFlight = maxInFlight;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(final long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getResponseTimeoutMs() {
            return responseTimeoutMs;
        }

        public void setResponseTimeoutMs(final long responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(final int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }

        public Aggregation getAggregation() {
            return aggregation;
        }

        public Tls getTls() {
            return tls;
        }

        public TranscriptResultStream getTranscriptResultStream() {
            return transcriptResultStream;
        }

        public Streaming getStreaming() {
            return streaming;
        }

        public static class Streaming {
            private boolean enabled = false;
            private String streamUrl = "";
            private int maxFrameBytes = 65_535;
            private int maxTerminalControlBytes = 64;
            private long terminalDrainTimeoutMs = 10_000L;

            void validate(final boolean tlsEnabled) {
                if (!enabled) {
                    return;
                }
                if (streamUrl == null || streamUrl.isBlank()) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.stream-url must be set when streaming is enabled");
                }
                final java.net.URI uri;
                try {
                    uri = java.net.URI.create(streamUrl.trim());
                } catch (IllegalArgumentException error) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.stream-url is not a valid URI",
                            error);
                }
                final String scheme = uri.getScheme();
                if (!"ws".equalsIgnoreCase(scheme) && !"wss".equalsIgnoreCase(scheme)) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.stream-url must use ws or wss");
                }
                if (tlsEnabled && !"wss".equalsIgnoreCase(scheme)) {
                    throw new IllegalStateException(
                            "direct-stt TLS requires a wss streaming URL");
                }
                if (maxFrameBytes <= 0 || maxFrameBytes > 65_535) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.max-frame-bytes must be in [1,65535]");
                }
                if (maxTerminalControlBytes < 14 || maxTerminalControlBytes > 1_024) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.max-terminal-control-bytes must be in [14,1024]");
                }
                if (terminalDrainTimeoutMs <= 0L || terminalDrainTimeoutMs > 60_000L) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.streaming.terminal-drain-timeout-ms must be in [1,60000]");
                }
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(final boolean enabled) {
                this.enabled = enabled;
            }

            public String getStreamUrl() {
                return streamUrl;
            }

            public void setStreamUrl(final String streamUrl) {
                this.streamUrl = streamUrl;
            }

            public int getMaxFrameBytes() {
                return maxFrameBytes;
            }

            public void setMaxFrameBytes(final int maxFrameBytes) {
                this.maxFrameBytes = maxFrameBytes;
            }

            public int getMaxTerminalControlBytes() {
                return maxTerminalControlBytes;
            }

            public void setMaxTerminalControlBytes(final int maxTerminalControlBytes) {
                this.maxTerminalControlBytes = maxTerminalControlBytes;
            }

            public long getTerminalDrainTimeoutMs() {
                return terminalDrainTimeoutMs;
            }

            public void setTerminalDrainTimeoutMs(final long terminalDrainTimeoutMs) {
                this.terminalDrainTimeoutMs = terminalDrainTimeoutMs;
            }
        }

        public static class Aggregation {
            private boolean enabled = true;
            private int windowSeconds = 5;
            private int maxBufferedSessions = 64;

            void validate() {
                if (!enabled) {
                    return;
                }
                if (windowSeconds < 5 || windowSeconds > 30) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.aggregation.window-seconds must be in [5,30], got "
                                    + windowSeconds);
                }
                if (maxBufferedSessions <= 0) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.aggregation.max-buffered-sessions must be positive, got "
                                    + maxBufferedSessions);
                }
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(final boolean enabled) {
                this.enabled = enabled;
            }

            public int getWindowSeconds() {
                return windowSeconds;
            }

            public void setWindowSeconds(final int windowSeconds) {
                this.windowSeconds = windowSeconds;
            }

            public int getMaxBufferedSessions() {
                return maxBufferedSessions;
            }

            public void setMaxBufferedSessions(final int maxBufferedSessions) {
                this.maxBufferedSessions = maxBufferedSessions;
            }
        }

        public static class Tls {
            /** Enables client-auth TLS for the direct-STT WebClient. */
            private boolean enabled = false;

            /** PEM CA bundle used to verify the live-stt/Caddy server certificate. */
            private String caCertificatePath = "";

            /** PEM client certificate presented by audio-gateway to the Caddy mTLS proxy. */
            private String clientCertificatePath = "";

            /** PEM private key matching {@link #clientCertificatePath}. */
            private String clientPrivateKeyPath = "";

            void validate() {
                if (!enabled) {
                    return;
                }
                requireReadableFile("audio.gateway.direct-stt.tls.ca-certificate-path",
                        caCertificatePath);
                requireReadableFile("audio.gateway.direct-stt.tls.client-certificate-path",
                        clientCertificatePath);
                requireReadableFile("audio.gateway.direct-stt.tls.client-private-key-path",
                        clientPrivateKeyPath);
            }

            private static void requireReadableFile(final String propertyName, final String value) {
                if (value == null || value.isBlank()) {
                    throw new IllegalStateException(propertyName + " must be set when direct-stt TLS is enabled");
                }
                final Path path = Path.of(value.trim());
                if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                    throw new IllegalStateException(propertyName + " must point to a readable file");
                }
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(final boolean enabled) {
                this.enabled = enabled;
            }

            public String getCaCertificatePath() {
                return caCertificatePath;
            }

            public void setCaCertificatePath(final String caCertificatePath) {
                this.caCertificatePath = caCertificatePath;
            }

            public String getClientCertificatePath() {
                return clientCertificatePath;
            }

            public void setClientCertificatePath(final String clientCertificatePath) {
                this.clientCertificatePath = clientCertificatePath;
            }

            public String getClientPrivateKeyPath() {
                return clientPrivateKeyPath;
            }

            public void setClientPrivateKeyPath(final String clientPrivateKeyPath) {
                this.clientPrivateKeyPath = clientPrivateKeyPath;
            }
        }

        public static class TranscriptResultStream {
            /** Required when direct-STT is enabled; emits parsed transcript text to Redis. */
            private boolean enabled = false;

            /** Redis stream key for direct-STT transcript result handoff. */
            private String streamKey = "transcript:direct-stt-results";

            /** Producer-side approximate trim. 0 means no trim; consumer/retention owns lifecycle. */
            private long maxLen = 0L;

            /** Max transcript events returned to a client per REST poll/SSE tick. */
            private int readBatchSize = 50;

            /** Max Redis stream entries scanned per client read window before advancing the cursor. */
            private int readMaxScan = 500;

            /** SSE poll cadence for bridging Redis result stream events to authenticated clients. */
            private long pollIntervalMs = 1_000L;

            /** SSE keepalive cadence; must stay below edge/proxy read timeout. */
            private long heartbeatIntervalMs = 15_000L;

            void validate() {
                if (!enabled) {
                    return;
                }
                if (streamKey == null || streamKey.isBlank()) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.transcript-result-stream.stream-key must be set "
                                    + "when transcript result streaming is enabled");
                }
                if (maxLen < 0) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.transcript-result-stream.max-len must be >= 0");
                }
                if (readBatchSize <= 0 || readMaxScan <= 0) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.transcript-result-stream read bounds must be positive");
                }
                if (readMaxScan < readBatchSize) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.transcript-result-stream.read-max-scan must be >= read-batch-size");
                }
                if (pollIntervalMs <= 0 || heartbeatIntervalMs <= 0) {
                    throw new IllegalStateException(
                            "audio.gateway.direct-stt.transcript-result-stream SSE intervals must be positive");
                }
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(final boolean enabled) {
                this.enabled = enabled;
            }

            public String getStreamKey() {
                return streamKey;
            }

            public void setStreamKey(final String streamKey) {
                this.streamKey = streamKey;
            }

            public long getMaxLen() {
                return maxLen;
            }

            public void setMaxLen(final long maxLen) {
                this.maxLen = maxLen;
            }

            public int getReadBatchSize() {
                return readBatchSize;
            }

            public void setReadBatchSize(final int readBatchSize) {
                this.readBatchSize = readBatchSize;
            }

            public int getReadMaxScan() {
                return readMaxScan;
            }

            public void setReadMaxScan(final int readMaxScan) {
                this.readMaxScan = readMaxScan;
            }

            public long getPollIntervalMs() {
                return pollIntervalMs;
            }

            public void setPollIntervalMs(final long pollIntervalMs) {
                this.pollIntervalMs = pollIntervalMs;
            }

            public long getHeartbeatIntervalMs() {
                return heartbeatIntervalMs;
            }

            public void setHeartbeatIntervalMs(final long heartbeatIntervalMs) {
                this.heartbeatIntervalMs = heartbeatIntervalMs;
            }
        }
    }

    public static class Jwt {
        private String tenantClaim = "companyId";
        private String userClaim = "userId";

        public String getTenantClaim() {
            return tenantClaim;
        }

        public void setTenantClaim(final String tenantClaim) {
            this.tenantClaim = tenantClaim;
        }

        public String getUserClaim() {
            return userClaim;
        }

        public void setUserClaim(final String userClaim) {
            this.userClaim = userClaim;
        }
    }

    /**
     * Meeting-service access validation for recorder start-session.
     *
     * <p>Local/default profile keeps this disabled so unit/contract tests do not require a
     * live meeting-service. The k8s profile enables it by default and points to the cluster
     * service DNS name. When enabled, startup fails for a blank/non-http base URL or
     * non-positive bounds; runtime meeting-service failure fails session start closed.
     */
    public static class MeetingAccess {
        private boolean validationEnabled = false;
        private String baseUrl = "http://localhost:8097";
        private long connectTimeoutMs = 2_000L;
        private long responseTimeoutMs = 3_000L;
        private int maxResponseBytes = 32_768;

        public void validate() {
            if (!validationEnabled) {
                return;
            }
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalStateException(
                        "audio.gateway.meeting-access.base-url must be set when validation is enabled");
            }
            final java.net.URI uri;
            try {
                uri = java.net.URI.create(baseUrl.trim());
            } catch (final IllegalArgumentException ex) {
                throw new IllegalStateException(
                        "audio.gateway.meeting-access.base-url is not a valid URI", ex);
            }
            final String scheme = uri.getScheme();
            if (scheme == null
                    || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
                throw new IllegalStateException(
                        "audio.gateway.meeting-access.base-url must be http or https, got scheme="
                                + scheme);
            }
            if (connectTimeoutMs <= 0 || responseTimeoutMs <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.meeting-access connect/response timeouts must be positive");
            }
            if (maxResponseBytes <= 0) {
                throw new IllegalStateException(
                        "audio.gateway.meeting-access.max-response-bytes must be positive, got "
                                + maxResponseBytes);
            }
        }

        public boolean isValidationEnabled() {
            return validationEnabled;
        }

        public void setValidationEnabled(final boolean validationEnabled) {
            this.validationEnabled = validationEnabled;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(final String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public long getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(final long connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public long getResponseTimeoutMs() {
            return responseTimeoutMs;
        }

        public void setResponseTimeoutMs(final long responseTimeoutMs) {
            this.responseTimeoutMs = responseTimeoutMs;
        }

        public int getMaxResponseBytes() {
            return maxResponseBytes;
        }

        public void setMaxResponseBytes(final int maxResponseBytes) {
            this.maxResponseBytes = maxResponseBytes;
        }
    }

    public static class Idempotency {
        private String headerName = "Idempotency-Key";
        private int minLength = 16;
        private int maxLength = 128;
        private int replayCacheSize = 4_096;

        public String getHeaderName() {
            return headerName;
        }

        public void setHeaderName(final String headerName) {
            this.headerName = headerName;
        }

        public int getMinLength() {
            return minLength;
        }

        public void setMinLength(final int minLength) {
            this.minLength = minLength;
        }

        public int getMaxLength() {
            return maxLength;
        }

        public void setMaxLength(final int maxLength) {
            this.maxLength = maxLength;
        }

        public int getReplayCacheSize() {
            return replayCacheSize;
        }

        public void setReplayCacheSize(final int replayCacheSize) {
            this.replayCacheSize = replayCacheSize;
        }
    }

    /**
     * Audit sink config — Faz 24 KVKK audit pipeline (gitops#1249).
     *
     * <p>The {@code AudioGatewayAuditSink} emission point is wired to a Redis
     * Stream producer ({@code RedisStreamAuditSink}) only when
     * {@code audio.gateway.audit.redis.enabled=true}. DEFAULT-OFF: the
     * {@link com.example.audiogateway.service.NoOpAudioGatewayAuditSink} stays
     * the bean otherwise, so live behaviour does not change until an overlay
     * flips the flag (mirrors the dispatcher {@code mode=noop|redis} discipline).
     *
     * <p>The audit stream key is independent of the audio chunk partitions
     * ({@code audio:chunks:pNN}) — audit events land on a single
     * {@code audit:events} stream consumed by {@code audit-event-consumer-service}.
     */
    public static class Audit {
        private final Redis redis = new Redis();

        public Redis getRedis() {
            return redis;
        }

        public void validate() {
            redis.validate();
        }

        public static class Redis {
            /** DEFAULT-OFF — see {@link Audit}. */
            private boolean enabled = false;
            /** Audit event stream key (consumer reads the same key). */
            private String streamKey = "audit:events";
            /**
             * Legacy compatibility property for the shared audit stream.
             *
             * <p><b>DEFAULT 0 = NO producer-side trim — and it must stay that
             * way for KVKK audit integrity.</b> A positive MAXLEN would let the
             * producer evict the oldest audit entries before the immutable
             * consumer has persisted them → audit-event loss (KVKK m.12 violation).
             * Stream growth is instead bounded by (a) the durable consumer atomically
             * ACKing and deleting each source entry after PostgreSQL persistence or
             * DLQ parking, and (b) its health indicator surfacing both XPENDING and
             * source-stream length thresholds. The 7yr archive/expiry of
             * persisted rows is the #1250 retention-worker follow-up, not a
             * producer trim. Every event type shares this stream, so any non-zero
             * value is rejected at startup and the producer never reads it.
             */
            private long maxLen = 0L;

            void validate() {
                if (maxLen != 0L) {
                    throw new IllegalStateException(
                            "audio.gateway.audit.redis.max-len must be 0; producer-side trimming "
                                    + "can evict unpersisted audit evidence");
                }
            }

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(final boolean enabled) {
                this.enabled = enabled;
            }

            public String getStreamKey() {
                return streamKey;
            }

            public void setStreamKey(final String streamKey) {
                this.streamKey = streamKey;
            }

            public long getMaxLen() {
                return maxLen;
            }

            public void setMaxLen(final long maxLen) {
                this.maxLen = maxLen;
            }
        }
    }
}
