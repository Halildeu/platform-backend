package com.example.transcript.attribution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the default-off session attribution consumer (#3746 BE-D4b).
 *
 * <p>The stream carries metadata only (labels + milliseconds — no audio, no text),
 * but the consumer still never starts unless {@code enabled=true}: attribution is a
 * per-environment rollout decision like every other #3746 flag.
 */
@ConfigurationProperties(prefix = "transcript.session-attribution-consumer")
public class SessionAttributionConsumerProperties {

    private boolean enabled = false;
    private String dlqStreamKey = "stt:session:attribution:dlq";
    private final Stream stream = new Stream();
    private final Group group = new Group();
    private final Poll poll = new Poll();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getDlqStreamKey() {
        return dlqStreamKey;
    }

    public void setDlqStreamKey(String dlqStreamKey) {
        this.dlqStreamKey = dlqStreamKey;
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

    public static class Stream {
        /** Must match live-stt's STT_ATTRIBUTION_STREAM (producer default). */
        private String key = "stt:session:attribution";

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Group {
        private String name = "transcript-attribution-v1";
        private String consumer = "transcript-service-1";

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getConsumer() {
            return consumer;
        }

        public void setConsumer(String consumer) {
            this.consumer = consumer;
        }
    }

    public static class Poll {
        private int batchSize = 16;
        private long blockMillis = 2_000L;
        private long errorBackoffMillis = 1_000L;
        private long claimMinIdleMillis = 60_000L;
        private int claimBatchSize = 16;

        public int getBatchSize() {
            return batchSize;
        }

        public void setBatchSize(int batchSize) {
            this.batchSize = batchSize;
        }

        public long getBlockMillis() {
            return blockMillis;
        }

        public void setBlockMillis(long blockMillis) {
            this.blockMillis = blockMillis;
        }

        public long getErrorBackoffMillis() {
            return errorBackoffMillis;
        }

        public void setErrorBackoffMillis(long errorBackoffMillis) {
            this.errorBackoffMillis = errorBackoffMillis;
        }

        public long getClaimMinIdleMillis() {
            return claimMinIdleMillis;
        }

        public void setClaimMinIdleMillis(long claimMinIdleMillis) {
            this.claimMinIdleMillis = claimMinIdleMillis;
        }

        public int getClaimBatchSize() {
            return claimBatchSize;
        }

        public void setClaimBatchSize(int claimBatchSize) {
            this.claimBatchSize = claimBatchSize;
        }
    }
}
