package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import com.example.ethics.model.EvidenceAttachment;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ChecksumAlgorithm;
import software.amazon.awssdk.services.s3.model.ChecksumMode;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ObjectLockMode;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;

@Component
@ConditionalOnProperty(name = "ethics.evidence.enabled", havingValue = "true")
public class S3EvidenceObjectStore implements EvidenceObjectStore {
    private static final HexFormat HEX = HexFormat.of();
    private final S3Client s3;
    private final EvidenceProperties properties;

    public S3EvidenceObjectStore(S3Client s3, EvidenceProperties properties) {
        this.s3 = s3;
        this.properties = properties;
    }

    @Override
    public ObjectReceipt putQuarantine(
            EvidenceAttachment attachment, InputStream input, long contentLength) {
        try {
            ObjectReceipt existing = existingExact(
                    properties.getS3().getQuarantineBucket(),
                    attachment.getQuarantineKey(),
                    attachment.getDeclaredSize(),
                    attachment.getDeclaredSha256());
            if (existing != null) return existing;
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            DigestInputStream verified = new DigestInputStream(input, digest);
            var request = PutObjectRequest.builder()
                    .bucket(properties.getS3().getQuarantineBucket())
                    .key(attachment.getQuarantineKey())
                    .contentType("application/octet-stream")
                    .contentLength(contentLength)
                    .checksumSHA256(hexToBase64(attachment.getDeclaredSha256()))
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();
            String version = s3.putObject(request, RequestBody.fromInputStream(verified, contentLength))
                    .versionId();
            String actual = HEX.formatHex(digest.digest());
            if (!MessageDigest.isEqual(
                    HEX.parseHex(actual), HEX.parseHex(attachment.getDeclaredSha256()))) {
                throw new StoreException("EVIDENCE_DIGEST_MISMATCH");
            }
            return verify(
                    properties.getS3().getQuarantineBucket(),
                    attachment.getQuarantineKey(),
                    version,
                    attachment.getDeclaredSize(),
                    attachment.getDeclaredSha256());
        } catch (StoreException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreException("EVIDENCE_STORAGE_UNAVAILABLE", error);
        }
    }

    @Override
    public ObjectReceipt sealOriginal(EvidenceAttachment attachment) {
        try {
            ObjectReceipt existing = existingExact(
                    properties.getS3().getSealedBucket(),
                    attachment.getSealedKey(),
                    attachment.getDeclaredSize(),
                    attachment.getDeclaredSha256());
            if (existing != null) return existing;
            var request = CopyObjectRequest.builder()
                    .copySource(properties.getS3().getQuarantineBucket() + "/" + attachment.getQuarantineKey())
                    .destinationBucket(properties.getS3().getSealedBucket())
                    .destinationKey(attachment.getSealedKey())
                    .checksumAlgorithm(ChecksumAlgorithm.SHA256)
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .objectLockMode(ObjectLockMode.COMPLIANCE)
                    .objectLockRetainUntilDate(Instant.now().plus(properties.getSealedRetention()))
                    .build();
            String version = s3.copyObject(request).versionId();
            return verify(
                    properties.getS3().getSealedBucket(),
                    attachment.getSealedKey(),
                    version,
                    attachment.getDeclaredSize(),
                    attachment.getDeclaredSha256());
        } catch (StoreException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreException("EVIDENCE_SEAL_UNAVAILABLE", error);
        }
    }

    @Override
    public byte[] readQuarantine(EvidenceAttachment attachment) {
        return readBounded(
                properties.getS3().getQuarantineBucket(),
                attachment.getQuarantineKey(),
                attachment.getDeclaredSize());
    }

    @Override
    public ObjectReceipt putDerivative(
            EvidenceAttachment attachment, byte[] content, String sha256, String mediaType) {
        try {
            ObjectReceipt existing = existingExact(
                    properties.getS3().getDerivativeBucket(),
                    attachment.getDerivativeKey(),
                    content.length,
                    sha256);
            if (existing != null) return existing;
            var request = PutObjectRequest.builder()
                    .bucket(properties.getS3().getDerivativeBucket())
                    .key(attachment.getDerivativeKey())
                    .contentType(mediaType)
                    .contentLength((long) content.length)
                    .checksumSHA256(hexToBase64(sha256))
                    .serverSideEncryption(ServerSideEncryption.AES256)
                    .build();
            String version = s3.putObject(request, RequestBody.fromBytes(content)).versionId();
            return verify(
                    properties.getS3().getDerivativeBucket(),
                    attachment.getDerivativeKey(),
                    version,
                    content.length,
                    sha256);
        } catch (StoreException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreException("EVIDENCE_DERIVATIVE_STORAGE_UNAVAILABLE", error);
        }
    }

    @Override
    public byte[] readDerivative(EvidenceAttachment attachment) {
        if (attachment.getDerivativeSize() == null) {
            throw new StoreException("EVIDENCE_DERIVATIVE_UNAVAILABLE");
        }
        return readBounded(
                properties.getS3().getDerivativeBucket(),
                attachment.getDerivativeKey(),
                attachment.getDerivativeSize());
    }

    @Override
    public void deleteQuarantine(EvidenceAttachment attachment) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder()
                    .bucket(properties.getS3().getQuarantineBucket())
                    .key(attachment.getQuarantineKey())
                    .build());
        } catch (Exception error) {
            throw new StoreException("EVIDENCE_QUARANTINE_DELETE_FAILED", error);
        }
    }

    private byte[] readBounded(String bucket, String key, long expectedSize) {
        if (expectedSize <= 0 || expectedSize > properties.getMaxBytes()) {
            throw new StoreException("EVIDENCE_SIZE_POLICY_FAILED");
        }
        try {
            ResponseBytes<GetObjectResponse> bytes = s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key).build());
            if (bytes.asByteArray().length != expectedSize) {
                throw new StoreException("EVIDENCE_STORED_SIZE_MISMATCH");
            }
            return bytes.asByteArray();
        } catch (StoreException error) {
            throw error;
        } catch (Exception error) {
            throw new StoreException("EVIDENCE_STORAGE_UNAVAILABLE", error);
        }
    }

    private ObjectReceipt verify(
            String bucket, String key, String versionId, long size, String sha256) {
        HeadObjectRequest.Builder request = HeadObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .checksumMode(ChecksumMode.ENABLED);
        if (versionId != null && !versionId.isBlank()) request.versionId(versionId);
        HeadObjectResponse head = s3.headObject(request.build());
        String stored = head.checksumSHA256() == null
                ? null
                : HEX.formatHex(Base64.getDecoder().decode(head.checksumSHA256()));
        if (head.contentLength() == null || head.contentLength() != size
                || stored == null || !stored.equalsIgnoreCase(sha256)
                || head.serverSideEncryption() != ServerSideEncryption.AES256) {
            throw new StoreException("EVIDENCE_OBJECT_VERIFICATION_FAILED");
        }
        return new ObjectReceipt(versionId, size, sha256.toLowerCase(java.util.Locale.ROOT));
    }

    /**
     * Crash-safe idempotency: a retry may adopt only a byte-identical existing
     * object. It never overwrites an object with a different checksum or size.
     */
    private ObjectReceipt existingExact(
            String bucket, String key, long size, String sha256) {
        try {
            HeadObjectResponse head = s3.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(key)
                    .checksumMode(ChecksumMode.ENABLED)
                    .build());
            String stored = head.checksumSHA256() == null
                    ? null
                    : HEX.formatHex(Base64.getDecoder().decode(head.checksumSHA256()));
            if (head.contentLength() == null || head.contentLength() != size
                    || stored == null || !stored.equalsIgnoreCase(sha256)
                    || head.serverSideEncryption() != ServerSideEncryption.AES256) {
                throw new StoreException("EVIDENCE_OBJECT_ALREADY_EXISTS");
            }
            return new ObjectReceipt(head.versionId(), size, sha256.toLowerCase(java.util.Locale.ROOT));
        } catch (NoSuchKeyException error) {
            return null;
        } catch (S3Exception error) {
            if (error.statusCode() == 404) return null;
            throw error;
        }
    }

    private static String hexToBase64(String hex) {
        return Base64.getEncoder().encodeToString(HEX.parseHex(hex));
    }
}
