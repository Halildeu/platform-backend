package com.example.endpointadmin.service;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * #527 slice-1 — validates failed-device-queue redacted evidence against the
 * MERGED contract JSON schema (Draft 2020-12), so the runtime contract and the
 * docs contract (validate.py) cannot drift. The schema file shipped on the
 * classpath is a byte-identical copy of
 * {@code docs/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json}
 * (asserted by {@code FailedDeviceQueueSchemaValidatorTest} via SHA-256).
 *
 * <p>Validation is against {@code $defs/evidence} (the per-class allowlisted
 * {@code oneOf} with the class discriminator + {@code additionalProperties:false}
 * redaction allowlist), extracted into a self-contained schema so its internal
 * {@code #/$defs/...} refs resolve without URI mapping.
 */
@Component
public class FailedDeviceQueueSchemaValidator {

    /** Classpath copy of the merged contract schema (single source of truth). */
    public static final String SCHEMA_RESOURCE =
            "/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json";

    private static final int MAX_REPORTED_ERRORS = 10;

    private final ObjectMapper mapper;
    private final JsonSchema evidenceSchema;

    public FailedDeviceQueueSchemaValidator(ObjectMapper mapper) {
        this.mapper = mapper;
        JsonNode full = readContractSchema(mapper);
        ObjectNode selfContained = mapper.createObjectNode();
        selfContained.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        selfContained.set("$defs", full.get("$defs"));
        selfContained.put("$ref", "#/$defs/evidence");
        JsonSchemaFactory factory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
        this.evidenceSchema = factory.getSchema(selfContained);
    }

    /**
     * Fail-closed validation of a per-class redacted evidence object. Throws
     * {@link IllegalArgumentException} (a 400-class failure) with a capped,
     * deterministically-ordered error summary when the evidence violates the
     * contract (wrong/missing class discriminator, off-allowlist key, missing
     * required field, non-redacted log excerpt, bad hash format, ...).
     */
    public void validateEvidence(RolloutFailureClass expectedClass, Map<String, Object> evidence) {
        if (expectedClass == null) {
            throw new IllegalArgumentException("failed-device-queue expectedClass is required");
        }
        if (evidence == null) {
            throw new IllegalArgumentException("failed-device-queue evidence is required");
        }
        JsonNode node = mapper.valueToTree(evidence);
        Set<ValidationMessage> errors = evidenceSchema.validate(node);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException(
                    "failed-device-queue evidence violates the contract schema: " + summarize(errors));
        }
        // Class binding (Codex 019eaaf3 must-fix 1): the evidence discriminator must
        // match the aggregate's current_class — a valid-but-wrong-class evidence is
        // rejected (the schema only binds evidence.class to its OWN shape).
        Object evidenceClass = evidence.get("class");
        if (!expectedClass.name().equals(evidenceClass)) {
            throw new IllegalArgumentException(
                    "failed-device-queue evidence.class (" + evidenceClass
                            + ") does not match the failure class (" + expectedClass.name() + ")");
        }
    }

    private static JsonNode readContractSchema(ObjectMapper mapper) {
        try (InputStream in = FailedDeviceQueueSchemaValidator.class.getResourceAsStream(SCHEMA_RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("contract schema not on classpath: " + SCHEMA_RESOURCE);
            }
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to read contract schema " + SCHEMA_RESOURCE, e);
        }
    }

    private static String summarize(Set<ValidationMessage> errors) {
        return errors.stream()
                .map(ValidationMessage::getMessage)
                .sorted(Comparator.naturalOrder())
                .limit(MAX_REPORTED_ERRORS)
                .collect(Collectors.joining("; "));
    }
}
