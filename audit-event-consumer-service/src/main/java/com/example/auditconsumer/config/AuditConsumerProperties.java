package com.example.auditconsumer.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for {@code audit.consumer.*}.
 *
 * <p>No hard-coded operational values — the stream key, consumer group/instance,
 * batch/block/claim tunables and the consumer-lag health threshold are all
 * overridable per overlay.
 */
@ConfigurationProperties(prefix = "audit.consumer")
public class AuditConsumerProperties {

    /**
     * Master switch for the Redis Streams consumer loop. DEFAULT-ON, but a unit
     * test / a deployment that only wants the persistence surface can set it
     * false to skip starting the poller (no Redis dependency at boot).
     */
    private boolean enabled = true;

    /**
     * Dead-letter stream key for poison/INVALID events. A malformed or
     * unmappable stream entry is XADDed here as a PII-minimal fingerprinted
     * diagnostic BEFORE it is ACKed, so an event that cannot be persisted is
     * parked — never silently dropped (KVKK audit-loss guard) and never wedged
     * in an infinite redelivery loop. If the DLQ XADD itself fails, the entry is
     * NOT ACKed and stays in the PEL for retry.
     */
    private String dlqStreamKey = "audit:events:dlq";
    /** Approximate maximum number of retained DLQ diagnostics. */
    private long dlqMaxLen = 10_000L;
    /** Redis key retention for the DLQ stream. Must be positive in active deployments. */
    private long dlqTtlSeconds = 604_800L;

    private final Stream stream = new Stream();
    private final Group group = new Group();
    private final Poll poll = new Poll();
    private final Health health = new Health();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(final boolean enabled) {
        this.enabled = enabled;
    }

    public String getDlqStreamKey() {
        return dlqStreamKey;
    }

    public void setDlqStreamKey(final String dlqStreamKey) {
        this.dlqStreamKey = dlqStreamKey;
    }

    public long getDlqMaxLen() {
        return dlqMaxLen;
    }

    public void setDlqMaxLen(final long dlqMaxLen) {
        this.dlqMaxLen = dlqMaxLen;
    }

    public long getDlqTtlSeconds() {
        return dlqTtlSeconds;
    }

    public void setDlqTtlSeconds(final long dlqTtlSeconds) {
        this.dlqTtlSeconds = dlqTtlSeconds;
    }

    public Stream getStream() {
        return stream;
    }

    public Group getGroup() {
        return group;
    }

    public Poll getPoll() {
        return poll;
    }

    public Health getHealth() {
        return health;
    }

    /** The audit stream the producer (audio-gateway) XADDs to. */
    public static class Stream {
        private String key = "audit:events";

        public String getKey() {
            return key;
        }

        public void setKey(final String key) {
            this.key = key;
        }
    }

    /** Consumer-group identity (XREADGROUP group/consumer). */
    public static class Group {
        private String name = "audit-persist-v2";
        /**
         * Consumer name within the group. Defaults to a stable per-instance id;
         * override with the pod name (e.g. {@code ${HOSTNAME}}) so PEL claim of a
         * crashed instance's pending entries is unambiguous.
         */
        private String consumer = "audit-consumer-1";

        public String getName() {
            return name;
        }

        public void setName(final String name) {
            this.name = name;
        }

        public String getConsumer() {
            return consumer;
        }

        public void setConsumer(final String consumer) {
            this.consumer = consumer;
        }
    }

    /** Poll loop tunables. */
    public static class Poll {
        /** Max entries per XREADGROUP batch. */
        private int batchSize = 64;
        /** XREADGROUP BLOCK timeout (ms). */
        private long blockMillis = 2_000L;
        /** Sleep between poll cycles after a Redis error, before retrying (ms). */
        private long errorBackoffMillis = 1_000L;
        /**
         * Min idle time (ms) before this consumer claims another consumer's
         * pending entry (XAUTOCLAIM/XCLAIM) — crashed-instance recovery.
         */
        private long claimMinIdleMillis = 60_000L;
        /** Max pending entries to reclaim per cycle. */
        private int claimBatchSize = 64;
        /** Delivery attempts before an unresolved predecessor is parked in the DLQ. */
        private long dependencyMaxDeliveryAttempts = 5L;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(final int batchSize) {
            this.batchSize = batchSize;
        }

        public long getBlockMillis() {
            return blockMillis;
        }

        public void setBlockMillis(final long blockMillis) {
            this.blockMillis = blockMillis;
        }

        public long getErrorBackoffMillis() {
            return errorBackoffMillis;
        }

        public void setErrorBackoffMillis(final long errorBackoffMillis) {
            this.errorBackoffMillis = errorBackoffMillis;
        }

        public long getClaimMinIdleMillis() {
            return claimMinIdleMillis;
        }

        public void setClaimMinIdleMillis(final long claimMinIdleMillis) {
            this.claimMinIdleMillis = claimMinIdleMillis;
        }

        public int getClaimBatchSize() {
            return claimBatchSize;
        }

        public void setClaimBatchSize(final int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
        }

        public long getDependencyMaxDeliveryAttempts() {
            return dependencyMaxDeliveryAttempts;
        }

        public void setDependencyMaxDeliveryAttempts(final long dependencyMaxDeliveryAttempts) {
            this.dependencyMaxDeliveryAttempts = dependencyMaxDeliveryAttempts;
        }
    }

    /** Consumer-lag health gate. */
    public static class Health {
        /**
         * Aggregate consumer-group pending (XPENDING) above which the consumer
         * health indicator reports DOWN. 0 disables the lag gate (health then
         * only reflects loop liveness + Redis reachability).
         */
        private long maxPendingForHealthy = 50_000L;
        /**
         * Source stream length at/above which health reports DOWN. Persisted or
         * DLQ-parked entries are deleted by the consumer, so sustained growth
         * indicates an unconsumed/backlogged transport even when PEL is small.
         * 0 disables this gate.
         */
        private long maxStreamLengthForHealthy = 50_000L;

        public long getMaxPendingForHealthy() {
            return maxPendingForHealthy;
        }

        public void setMaxPendingForHealthy(final long maxPendingForHealthy) {
            this.maxPendingForHealthy = maxPendingForHealthy;
        }

        public long getMaxStreamLengthForHealthy() {
            return maxStreamLengthForHealthy;
        }

        public void setMaxStreamLengthForHealthy(final long maxStreamLengthForHealthy) {
            this.maxStreamLengthForHealthy = maxStreamLengthForHealthy;
        }
    }
}
