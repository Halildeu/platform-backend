package com.example.auditretention.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — audit-retention-worker config
 * (ADR-0042 D4 contract knobs).
 */
@ConfigurationProperties(prefix = "audit.retention")
public class AuditRetentionProperties {

    /** Run a single archive pass then exit (CronJob mode). */
    private boolean runOnce = true;

    private final Scheduler scheduler = new Scheduler();

    /** A row is eligible (cooled) once {@code event_timestamp < now() - hotWindowDays}. */
    private int hotWindowDays = 90;

    /** WORM retention horizon in years (KVKK audit-archive 7yr). */
    private int retentionYears = 7;

    /** Rows fetched per DB scan batch. */
    private int scanBatchSize = 5000;

    /** Max rows per emitted archive object (segment). */
    private int maxSegmentRows = 50000;

    private String sourceSchema = "audit_event";
    private String sourceTable = "audit_event";

    /** Image digest stamped into the manifest/ledger (set by deploy). */
    private String workerImageDigest = "";

    private final S3 s3 = new S3();

    public boolean isRunOnce() {
        return runOnce;
    }

    public void setRunOnce(boolean runOnce) {
        this.runOnce = runOnce;
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public int getHotWindowDays() {
        return hotWindowDays;
    }

    public void setHotWindowDays(int hotWindowDays) {
        this.hotWindowDays = hotWindowDays;
    }

    public int getRetentionYears() {
        return retentionYears;
    }

    public void setRetentionYears(int retentionYears) {
        this.retentionYears = retentionYears;
    }

    public int getScanBatchSize() {
        return scanBatchSize;
    }

    public void setScanBatchSize(int scanBatchSize) {
        this.scanBatchSize = scanBatchSize;
    }

    public int getMaxSegmentRows() {
        return maxSegmentRows;
    }

    public void setMaxSegmentRows(int maxSegmentRows) {
        this.maxSegmentRows = maxSegmentRows;
    }

    public String getSourceSchema() {
        return sourceSchema;
    }

    public void setSourceSchema(String sourceSchema) {
        this.sourceSchema = sourceSchema;
    }

    public String getSourceTable() {
        return sourceTable;
    }

    public void setSourceTable(String sourceTable) {
        this.sourceTable = sourceTable;
    }

    public String getWorkerImageDigest() {
        return workerImageDigest;
    }

    public void setWorkerImageDigest(String workerImageDigest) {
        this.workerImageDigest = workerImageDigest;
    }

    public S3 getS3() {
        return s3;
    }

    /**
     * Fully-qualified, double-quoted source table reference. The schema/table
     * come from trusted config (not request input); double-quoting guards
     * against identifier-special-character surprises.
     */
    public String qualifiedSourceTable() {
        return "\"" + sourceSchema.replace("\"", "\"\"") + "\".\""
                + sourceTable.replace("\"", "\"\"") + "\"";
    }

    public static class Scheduler {
        /** Deployment mode: a fixed-delay scheduled archive trigger (mutually exclusive with runOnce). */
        private boolean enabled = false;
        private long fixedDelayMillis = 900_000L;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public long getFixedDelayMillis() {
            return fixedDelayMillis;
        }

        public void setFixedDelayMillis(long fixedDelayMillis) {
            this.fixedDelayMillis = fixedDelayMillis;
        }
    }

    public static class S3 {
        private String endpoint = "http://localhost:9000";
        private String region = "us-east-1";
        private String bucket = "audit-archive";
        private String keyPrefix = "segments";
        private boolean pathStyleAccess = true;
        private String accessKey = "";
        private String secretKey = "";

        public String getEndpoint() {
            return endpoint;
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint;
        }

        public String getRegion() {
            return region;
        }

        public void setRegion(String region) {
            this.region = region;
        }

        public String getBucket() {
            return bucket;
        }

        public void setBucket(String bucket) {
            this.bucket = bucket;
        }

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isPathStyleAccess() {
            return pathStyleAccess;
        }

        public void setPathStyleAccess(boolean pathStyleAccess) {
            this.pathStyleAccess = pathStyleAccess;
        }

        public String getAccessKey() {
            return accessKey;
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey;
        }

        public String getSecretKey() {
            return secretKey;
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey;
        }
    }
}
