package com.example.audiogateway.config;

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
        dispatcher.validate();
        directStt.validate();
    }


    private final Contract contract = new Contract();
    private final Bounds bounds = new Bounds();
    private final Dispatcher dispatcher = new Dispatcher();
    private final Jwt jwt = new Jwt();
    private final Idempotency idempotency = new Idempotency();
    private final Audit audit = new Audit();
    private final DirectStt directStt = new DirectStt();

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
        private int admissionQueueCapacity = 1_000;
        private int maxActiveSessions = 1_000;

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
         * Fail-closed validation (Codex {@code 019eeb5f} REVISE point 10): when enabled,
         * a missing/blank/non-http(s) {@code transcribe-url} or a non-positive bound is a
         * startup error — silent fallback that hides misconfiguration is YASAK.
         */
        public void validate() {
            if (!enabled) {
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
     * {@code audiogateway.audit.redis.enabled=true}. DEFAULT-OFF: the
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

        public static class Redis {
            /** DEFAULT-OFF — see {@link Audit}. */
            private boolean enabled = false;
            /** Audit event stream key (consumer reads the same key). */
            private String streamKey = "audit:events";
            /**
             * Optional MAXLEN cap on the audit stream (approximate trim).
             *
             * <p><b>DEFAULT 0 = NO producer-side trim — and it must stay that
             * way for KVKK audit integrity.</b> A positive MAXLEN would let the
             * producer evict the oldest audit entries before the immutable
             * consumer has persisted them → audit-event loss (KVKK m.12 violation).
             * Stream growth is instead bounded by (a) the consumer keeping up
             * (its consumer-lag health indicator surfaces backpressure / XPENDING
             * over threshold as DOWN) and (b) poison entries being parked in the
             * consumer's DLQ stream rather than looping. The 7yr archive/expiry of
             * persisted rows is the #1250 retention-worker follow-up, not a
             * producer trim. Only set a cap if a non-KVKK stream ever reuses this
             * sink.
             */
            private long maxLen = 0L;

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
