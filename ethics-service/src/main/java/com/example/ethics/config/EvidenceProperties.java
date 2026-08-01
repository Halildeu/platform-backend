package com.example.ethics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-104 evidence pipeline controls. The feature is fail-closed and disabled
 * until an overlay supplies isolated object-store credentials, a manifest
 * signing key and an accepted scanner/sanitizer endpoint.
 */
@ConfigurationProperties(prefix = "ethics.evidence")
public class EvidenceProperties {
    private boolean enabled;
    private String policyVersion = "faz35-evidence-custody/v1";
    private long maxBytes = 26_214_400L;
    private Duration uploadCapabilityTtl = Duration.ofMinutes(10);
    private Duration sealedRetention = Duration.ofDays(30);
    private String manifestSigningKey = "";
    private final Pipeline pipeline = new Pipeline();
    private final Processor processor = new Processor();
    private final S3 s3 = new S3();

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getPolicyVersion() { return policyVersion; }
    public void setPolicyVersion(String policyVersion) { this.policyVersion = policyVersion; }
    public long getMaxBytes() { return maxBytes; }
    public void setMaxBytes(long maxBytes) { this.maxBytes = maxBytes; }
    public Duration getUploadCapabilityTtl() { return uploadCapabilityTtl; }
    public void setUploadCapabilityTtl(Duration uploadCapabilityTtl) { this.uploadCapabilityTtl = uploadCapabilityTtl; }
    public Duration getSealedRetention() { return sealedRetention; }
    public void setSealedRetention(Duration sealedRetention) { this.sealedRetention = sealedRetention; }
    public String getManifestSigningKey() { return manifestSigningKey; }
    public void setManifestSigningKey(String manifestSigningKey) { this.manifestSigningKey = manifestSigningKey; }
    public Pipeline getPipeline() { return pipeline; }
    public Processor getProcessor() { return processor; }
    public S3 getS3() { return s3; }

    public void requireRuntimeConfiguration() {
        if (!enabled) return;
        if (policyVersion == null || policyVersion.isBlank()) {
            throw new IllegalStateException("ethics.evidence.policy-version is required");
        }
        if (maxBytes <= 0 || maxBytes > 26_214_400L) {
            throw new IllegalStateException("ethics.evidence.max-bytes exceeds the custody contract");
        }
        if (manifestSigningKey == null || manifestSigningKey.length() < 32) {
            throw new IllegalStateException("ethics.evidence.manifest-signing-key must be supplied by the secret store");
        }
        s3.requireRuntimeConfiguration();
    }

    public static class Pipeline {
        private boolean enabled;
        private Duration pollDelay = Duration.ofSeconds(5);
        private int batchSize = 10;
        private Duration leaseDuration = Duration.ofMinutes(2);
        private Duration retryDelay = Duration.ofMinutes(1);
        private int maxAttempts = 8;
        /**
         * ES-104J (#2929): which media lane this worker instance drains.
         * {@code all} (default) keeps single-worker deployments whole; {@code core}
         * excludes PDFs; {@code pdf} takes only PDFs. Two deployments on disjoint
         * lanes is what keeps a PDF bomb from stalling the text/image lane — the
         * #2860 OOM took the whole line down precisely because there was one lane.
         */
        private String lane = "all";

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getLane() { return lane; }
        public void setLane(String lane) {
            if (!java.util.Set.of("all", "core", "pdf").contains(lane)) {
                throw new IllegalArgumentException(
                        "ethics.evidence.pipeline.lane must be all|core|pdf, got: " + lane);
            }
            this.lane = lane;
        }
        public Duration getPollDelay() { return pollDelay; }
        public void setPollDelay(Duration pollDelay) { this.pollDelay = pollDelay; }
        public int getBatchSize() { return batchSize; }
        public void setBatchSize(int batchSize) { this.batchSize = batchSize; }
        public Duration getLeaseDuration() { return leaseDuration; }
        public void setLeaseDuration(Duration leaseDuration) { this.leaseDuration = leaseDuration; }
        public Duration getRetryDelay() { return retryDelay; }
        public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }
    }

    public static class Processor {
        private String mode = "disabled";
        private String clamavHost = "localhost";
        private int clamavPort = 3310;
        private Duration timeout = Duration.ofSeconds(20);
        private long maxDecodedImagePixels = 40_000_000L;
        /** ES-104J: page-count ceiling — the cheap half of the PDF-bomb defence. */
        private int maxPdfPages = 100;
        /** ES-104J: render density; with A4 at 150 DPI a page stays ~2.2M pixels. */
        private int pdfRenderDpi = 150;
        /** ES-104K dilim 2: empty means HEIC stays fail-closed. */
        private String heicConverterUrl = "";
        private String scannerDigest = "";
        private String sanitizerDigest = "";
        private String parserDigest = "";
        private String rulesVersion = "";
        private String transformationProfile = "faz35-baseline-v1";

        public String getMode() { return mode; }
        public void setMode(String mode) { this.mode = mode; }
        public String getClamavHost() { return clamavHost; }
        public void setClamavHost(String clamavHost) { this.clamavHost = clamavHost; }
        public int getClamavPort() { return clamavPort; }
        public void setClamavPort(int clamavPort) { this.clamavPort = clamavPort; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public long getMaxDecodedImagePixels() { return maxDecodedImagePixels; }
        public void setMaxDecodedImagePixels(long maxDecodedImagePixels) { this.maxDecodedImagePixels = maxDecodedImagePixels; }
        public int getMaxPdfPages() { return maxPdfPages; }
        public void setMaxPdfPages(int maxPdfPages) { this.maxPdfPages = maxPdfPages; }
        public int getPdfRenderDpi() { return pdfRenderDpi; }
        public void setPdfRenderDpi(int pdfRenderDpi) { this.pdfRenderDpi = pdfRenderDpi; }
        public String getHeicConverterUrl() { return heicConverterUrl; }
        public void setHeicConverterUrl(String heicConverterUrl) { this.heicConverterUrl = heicConverterUrl; }
        public String getScannerDigest() { return scannerDigest; }
        public void setScannerDigest(String scannerDigest) { this.scannerDigest = scannerDigest; }
        public String getSanitizerDigest() { return sanitizerDigest; }
        public void setSanitizerDigest(String sanitizerDigest) { this.sanitizerDigest = sanitizerDigest; }
        public String getParserDigest() { return parserDigest; }
        public void setParserDigest(String parserDigest) { this.parserDigest = parserDigest; }
        public String getRulesVersion() { return rulesVersion; }
        public void setRulesVersion(String rulesVersion) { this.rulesVersion = rulesVersion; }
        public String getTransformationProfile() { return transformationProfile; }
        public void setTransformationProfile(String transformationProfile) { this.transformationProfile = transformationProfile; }
    }

    public static class S3 {
        private String endpoint = "http://localhost:9000";
        private String region = "us-east-1";
        private boolean pathStyleAccess = true;
        private String accessKey = "";
        private String secretKey = "";
        private String quarantineBucket = "ethics-evidence-quarantine";
        private String sealedBucket = "ethics-evidence-sealed";
        private String derivativeBucket = "ethics-evidence-derivative";
        private String serverSideEncryption = "AES256";

        public String getEndpoint() { return endpoint; }
        public void setEndpoint(String endpoint) { this.endpoint = endpoint; }
        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }
        public boolean isPathStyleAccess() { return pathStyleAccess; }
        public void setPathStyleAccess(boolean pathStyleAccess) { this.pathStyleAccess = pathStyleAccess; }
        public String getAccessKey() { return accessKey; }
        public void setAccessKey(String accessKey) { this.accessKey = accessKey; }
        public String getSecretKey() { return secretKey; }
        public void setSecretKey(String secretKey) { this.secretKey = secretKey; }
        public String getQuarantineBucket() { return quarantineBucket; }
        public void setQuarantineBucket(String quarantineBucket) { this.quarantineBucket = quarantineBucket; }
        public String getSealedBucket() { return sealedBucket; }
        public void setSealedBucket(String sealedBucket) { this.sealedBucket = sealedBucket; }
        public String getDerivativeBucket() { return derivativeBucket; }
        public void setDerivativeBucket(String derivativeBucket) { this.derivativeBucket = derivativeBucket; }
        public String getServerSideEncryption() { return serverSideEncryption; }
        public void setServerSideEncryption(String serverSideEncryption) { this.serverSideEncryption = serverSideEncryption; }

        private void requireRuntimeConfiguration() {
            if (endpoint == null || endpoint.isBlank()
                    || region == null || region.isBlank()
                    || accessKey == null || accessKey.isBlank()
                    || secretKey == null || secretKey.isBlank()
                    || quarantineBucket == null || quarantineBucket.isBlank()
                    || sealedBucket == null || sealedBucket.isBlank()
                    || derivativeBucket == null || derivativeBucket.isBlank()
                    || !"AES256".equals(serverSideEncryption)) {
                throw new IllegalStateException("ethics.evidence.s3 configuration is incomplete");
            }
        }
    }
}
