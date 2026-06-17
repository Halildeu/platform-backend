package com.example.auditretention.archive;

import com.example.auditretention.config.AuditRetentionProperties;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — S3 archive object writer/verifier
 * (ADR-0042 D4.7, AWS SDK v2 over MinIO).
 *
 * <p>Implements the version-id idempotency contract: every PutObject carries a
 * COMPLIANCE retain-until + an {@code x-amz-checksum-sha256}; the response
 * {@code versionId} is captured for the ledger. Verification is always
 * version-specific (HEAD by versionId) and compares the stored checksum (NOT
 * the bare ETag). {@link #headLatest} exposes the latest version id so the
 * orchestrator can assert {@code latest == ledger.versionId} (tamper/anomaly).
 */
@Component
public class S3ArchiveClient {

    private static final HexFormat HEX = HexFormat.of();

    private final S3Client s3;
    private final AuditRetentionProperties props;

    public S3ArchiveClient(S3Client s3, AuditRetentionProperties props) {
        this.s3 = s3;
        this.props = props;
    }

    private String bucket() {
        return props.getS3().getBucket();
    }

    /** HEAD result projection (version-specific or latest). */
    public record ObjectHead(String versionId, long contentLength, String checksumSha256Hex,
                             ObjectLockMode objectLockMode, Instant retainUntil) {
    }

    /**
     * Put an object under COMPLIANCE retention with an explicit SHA-256 checksum.
     * Returns the created version id.
     */
    public String putObject(String key, byte[] body, String sha256Hex, Instant retainUntil) {
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(bucket())
                .key(key)
                .checksumSHA256(hexToBase64(sha256Hex))
                .objectLockMode(ObjectLockMode.COMPLIANCE)
                .objectLockRetainUntilDate(retainUntil)
                .contentType("application/octet-stream")
                .build();
        PutObjectResponse resp = s3.putObject(req, RequestBody.fromBytes(body));
        return resp.versionId();
    }

    /** HEAD a specific version. Returns empty if that version does not exist. */
    public Optional<ObjectHead> headVersion(String key, String versionId) {
        try {
            HeadObjectResponse r = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket()).key(key).versionId(versionId)
                    .checksumMode(ChecksumMode.ENABLED).build());
            return Optional.of(toHead(r));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (is404(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** HEAD the latest version. Returns empty if the key does not exist at all. */
    public Optional<ObjectHead> headLatest(String key) {
        try {
            HeadObjectResponse r = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket()).key(key)
                    .checksumMode(ChecksumMode.ENABLED).build());
            return Optional.of(toHead(r));
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        } catch (S3Exception e) {
            if (is404(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    public boolean objectExists(String key) {
        return headLatest(key).isPresent();
    }

    private static ObjectHead toHead(HeadObjectResponse r) {
        return new ObjectHead(
                r.versionId(),
                r.contentLength() == null ? -1L : r.contentLength(),
                r.checksumSHA256() == null ? null : base64ToHex(r.checksumSHA256()),
                r.objectLockMode(),
                r.objectLockRetainUntilDate());
    }

    private static boolean is404(S3Exception e) {
        return e.statusCode() == 404;
    }

    static String hexToBase64(String hex) {
        return Base64.getEncoder().encodeToString(HEX.parseHex(hex));
    }

    static String base64ToHex(String b64) {
        return HEX.formatHex(Base64.getDecoder().decode(b64));
    }
}
