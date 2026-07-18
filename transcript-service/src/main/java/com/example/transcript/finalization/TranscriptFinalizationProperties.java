package com.example.transcript.finalization;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Rollout and timing controls for recording-finished finalization. */
@ConfigurationProperties(prefix = "transcript.finalization")
public class TranscriptFinalizationProperties {

    private final RecordingFinishedConsumer recordingFinishedConsumer =
            new RecordingFinishedConsumer();
    private final Worker worker = new Worker();
    private final Timing timing = new Timing();

    public RecordingFinishedConsumer getRecordingFinishedConsumer() {
        return recordingFinishedConsumer;
    }

    public Worker getWorker() { return worker; }
    public Timing getTiming() { return timing; }

    @PostConstruct
    void validate() {
        if (recordingFinishedConsumer.enabled != worker.enabled) {
            throw new IllegalStateException(
                    "Recording-finished consumer and finalization worker must be enabled together.");
        }
        if (timing.quiescence.isZero() || timing.quiescence.isNegative()) {
            throw new IllegalStateException("Transcript quiescence must be positive.");
        }
        if (timing.minWait.compareTo(Duration.ofMinutes(5)) <= 0) {
            throw new IllegalStateException(
                    "Transcript min-wait must exceed the observed five-minute late-result boundary.");
        }
        if (timing.maxWait.compareTo(timing.minWait.plus(timing.quiescence)) < 0) {
            throw new IllegalStateException(
                    "Transcript max-wait must cover min-wait plus one quiescence interval.");
        }
        if (worker.batchSize < 1 || worker.pollDelayMs < 1) {
            throw new IllegalStateException("Transcript finalization worker settings must be positive.");
        }
    }

    public static class RecordingFinishedConsumer {
        private boolean enabled;
        private String dlqStreamKey = "meeting:events:transcript-finalization:dlq";
        private String streamKey = "meeting:events";
        private String groupName = "transcript-finalization-v1";
        private String consumerName = "transcript-service-1";
        private int batchSize = 32;
        private long blockMillis = 2_000L;
        private long errorBackoffMillis = 1_000L;
        private long claimMinIdleMillis = 60_000L;
        private int claimBatchSize = 32;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public String getDlqStreamKey() { return dlqStreamKey; }
        public void setDlqStreamKey(String value) { dlqStreamKey = value; }
        public String getStreamKey() { return streamKey; }
        public void setStreamKey(String value) { streamKey = value; }
        public String getGroupName() { return groupName; }
        public void setGroupName(String value) { groupName = value; }
        public String getConsumerName() { return consumerName; }
        public void setConsumerName(String value) { consumerName = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = value; }
        public long getBlockMillis() { return blockMillis; }
        public void setBlockMillis(long value) { blockMillis = value; }
        public long getErrorBackoffMillis() { return errorBackoffMillis; }
        public void setErrorBackoffMillis(long value) { errorBackoffMillis = value; }
        public long getClaimMinIdleMillis() { return claimMinIdleMillis; }
        public void setClaimMinIdleMillis(long value) { claimMinIdleMillis = value; }
        public int getClaimBatchSize() { return claimBatchSize; }
        public void setClaimBatchSize(int value) { claimBatchSize = value; }
    }

    public static class Worker {
        private boolean enabled;
        private long pollDelayMs = 5_000L;
        private int batchSize = 50;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean value) { enabled = value; }
        public long getPollDelayMs() { return pollDelayMs; }
        public void setPollDelayMs(long value) { pollDelayMs = value; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int value) { batchSize = value; }
    }

    public static class Timing {
        private Duration quiescence = Duration.ofMinutes(1);
        private Duration minWait = Duration.ofMinutes(6);
        private Duration maxWait = Duration.ofMinutes(15);

        public Duration getQuiescence() { return quiescence; }
        public void setQuiescence(Duration value) { quiescence = value; }
        public Duration getMinWait() { return minWait; }
        public void setMinWait(Duration value) { minWait = value; }
        public Duration getMaxWait() { return maxWait; }
        public void setMaxWait(Duration value) { maxWait = value; }
    }
}
