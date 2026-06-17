package com.example.auditretention.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

import java.net.URI;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — AWS SDK v2 S3 client targeting the
 * host-compose MinIO (ADR-0042 D4.7 / Codex C(d)). Path-style + endpoint
 * override are mandatory for MinIO; the SDK gives first-class Object Lock
 * retention, versioning, {@code x-amz-version-id} and
 * {@code x-amz-checksum-sha256} support.
 */
@Configuration
public class S3Config {

    @Bean
    public S3Client s3Client(AuditRetentionProperties props) {
        AuditRetentionProperties.S3 s3 = props.getS3();
        return S3Client.builder()
                .endpointOverride(URI.create(s3.getEndpoint()))
                .region(Region.of(s3.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3.getAccessKey(), s3.getSecretKey())))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(s3.isPathStyleAccess())
                        .build())
                .build();
    }
}
