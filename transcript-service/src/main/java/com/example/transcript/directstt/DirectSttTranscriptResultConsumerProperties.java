package com.example.transcript.directstt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed binding for the default-off direct-STT transcript result consumer.
 *
 * <p>The stream carries transcript text, so operational knobs are explicit and
 * the consumer never starts unless {@code enabled=true}.
 */
@ConfigurationProperties(prefix = "transcript.direct-stt-result-consumer")
public class DirectSttTranscriptResultConsumerProperties {

    private boolean enabled = false;
    private String dlqStreamKey = "transcript:direct-stt-results:dlq";
    private final Stream stream = new Stream();
    private final Group group = new Group();
    private final Poll poll = new Poll();
    private final Mapping mapping = new Mapping();

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

    public Mapping getMapping() {
        return mapping;
    }

    public static class Stream {
        private String key = "transcript:direct-stt-results";

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }
    }

    public static class Group {
        private String name = "transcript-service-v1";
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
        private int batchSize = 32;
        private long blockMillis = 2_000L;
        private long errorBackoffMillis = 1_000L;
        private long claimMinIdleMillis = 60_000L;
        private int claimBatchSize = 32;

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

    /** Canonical meeting-session resolver + bounded reconciliation controls. */
    public static class Mapping {
        private boolean enabled = false;
        private String meetingServiceBaseUrl = "http://meeting-service:8097";
        private String tokenUrl = "http://auth-service:8088/oauth2/token";
        private String clientId = "transcript-service";
        private String clientSecret = "";
        private int connectTimeoutMillis = 2_000;
        private int responseTimeoutMillis = 3_000;
        private int maxAttempts = 8;
        private long initialBackoffMillis = 5_000L;
        private long maxBackoffMillis = 300_000L;
        private long claimLeaseMillis = 30_000L;
        private int reconciliationBatchSize = 50;
        private long reconciliationPollMillis = 30_000L;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getMeetingServiceBaseUrl() { return meetingServiceBaseUrl; }
        public void setMeetingServiceBaseUrl(String value) { this.meetingServiceBaseUrl = value; }
        public String getTokenUrl() { return tokenUrl; }
        public void setTokenUrl(String value) { this.tokenUrl = value; }
        public String getClientId() { return clientId; }
        public void setClientId(String value) { this.clientId = value; }
        public String getClientSecret() { return clientSecret; }
        public void setClientSecret(String value) { this.clientSecret = value; }
        public int getConnectTimeoutMillis() { return connectTimeoutMillis; }
        public void setConnectTimeoutMillis(int value) { this.connectTimeoutMillis = value; }
        public int getResponseTimeoutMillis() { return responseTimeoutMillis; }
        public void setResponseTimeoutMillis(int value) { this.responseTimeoutMillis = value; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int value) { this.maxAttempts = value; }
        public long getInitialBackoffMillis() { return initialBackoffMillis; }
        public void setInitialBackoffMillis(long value) { this.initialBackoffMillis = value; }
        public long getMaxBackoffMillis() { return maxBackoffMillis; }
        public void setMaxBackoffMillis(long value) { this.maxBackoffMillis = value; }
        public long getClaimLeaseMillis() { return claimLeaseMillis; }
        public void setClaimLeaseMillis(long value) { this.claimLeaseMillis = value; }
        public int getReconciliationBatchSize() { return reconciliationBatchSize; }
        public void setReconciliationBatchSize(int value) { this.reconciliationBatchSize = value; }
        public long getReconciliationPollMillis() { return reconciliationPollMillis; }
        public void setReconciliationPollMillis(long value) { this.reconciliationPollMillis = value; }
    }
}
