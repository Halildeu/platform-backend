package com.example.endpointadmin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #527 slice-1 — the runtime schema validator must enforce the SAME contract the
 * docs {@code validate.py} enforces. The classpath schema is asserted
 * byte-identical (SHA-256) to the docs original so the two cannot drift; the
 * evidence allowlist + class discriminator are exercised positively + negatively.
 */
class FailedDeviceQueueSchemaValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final FailedDeviceQueueSchemaValidator VALIDATOR =
            new FailedDeviceQueueSchemaValidator(MAPPER);

    /** Surefire cwd is the module dir; the docs original is the contract source of truth. */
    private static final Path DOCS_SCHEMA =
            Path.of("..", "docs", "contracts", "faz-22-failed-device-queue", "failed-device-queue.schema.json");

    private static Map<String, Object> goodHmacEvidence() {
        Map<String, Object> e = new HashMap<>();
        e.put("class", "SERVICE_HMAC_MODE");
        e.put("device_id", "d0efb00a-681a-4e32-b7de-a27ef94f2977");
        e.put("service_state", "running");
        e.put("agent_mode", "hmac");
        e.put("hmac_error_code", "HMAC_CONN_RESET");
        e.put("last_heartbeat_at", null);
        e.put("command_id", null);
        e.put("agent_version", "0.2.0");
        return e;
    }

    @Test
    void classpathSchemaIsByteIdenticalToTheDocsContract() throws Exception {
        try (InputStream in = FailedDeviceQueueSchemaValidator.class
                .getResourceAsStream(FailedDeviceQueueSchemaValidator.SCHEMA_RESOURCE)) {
            assertThat(in).as("contract schema must be on the classpath").isNotNull();
            byte[] classpath = in.readAllBytes();
            byte[] docs = Files.readAllBytes(DOCS_SCHEMA);
            assertThat(sha256(classpath))
                    .as("classpath schema must not drift from the docs contract")
                    .isEqualTo(sha256(docs));
        }
    }

    @Test
    void acceptsAllowlistedPerClassEvidence() {
        VALIDATOR.validateEvidence(goodHmacEvidence());
    }

    @Test
    void rejectsOffAllowlistKey() {
        Map<String, Object> bad = goodHmacEvidence();
        bad.put("raw_last_error", "secret token leaked");
        assertThatThrownBy(() -> VALIDATOR.validateEvidence(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("contract schema");
    }

    @Test
    void rejectsMissingRequiredField() {
        Map<String, Object> bad = goodHmacEvidence();
        bad.remove("agent_version");
        assertThatThrownBy(() -> VALIDATOR.validateEvidence(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsClassDiscriminatorMismatch() {
        // SERVICE_HMAC_MODE body but tagged as an INSTALLER_MSI class.
        Map<String, Object> bad = goodHmacEvidence();
        bad.put("class", "INSTALLER_MSI");
        assertThatThrownBy(() -> VALIDATOR.validateEvidence(bad))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
