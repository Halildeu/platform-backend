package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard (Codex 019eaaf0): the code-defined per-class evidence allowlist in
 * {@link RolloutFailureEvidenceValidator#SPEC} MUST stay byte-for-field identical
 * to the committed contract schema
 * ({@code docs/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json}
 * {@code $defs.evidence.oneOf}). Without this, the typed validator and the
 * machine-enforced contract could silently diverge — exactly the failure the
 * contract gate exists to prevent.
 */
class RolloutFailureEvidenceSchemaDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Path locateSchema() {
        // Surefire runs from the module dir; the contract lives at repo root.
        for (String candidate : new String[]{
                "../docs/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json",
                "docs/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json"}) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) {
                return p;
            }
        }
        // Walk upward as a last resort.
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++, dir = dir.getParent()) {
            Path p = dir.resolve("docs/contracts/faz-22-failed-device-queue/failed-device-queue.schema.json");
            if (Files.exists(p)) {
                return p;
            }
        }
        throw new IllegalStateException("contract schema.json not found from " + Paths.get("").toAbsolutePath());
    }

    @Test
    void validatorAllowlistMatchesContractSchemaPerClass() throws IOException {
        JsonNode schema = MAPPER.readTree(Files.readString(locateSchema()));
        JsonNode oneOf = schema.path("$defs").path("evidence").path("oneOf");
        assertThat(oneOf.isArray()).as("evidence.oneOf present").isTrue();

        Set<String> schemaClasses = new HashSet<>();
        for (JsonNode variant : oneOf) {
            String className = variant.path("properties").path("class").path("const").asText();
            schemaClasses.add(className);
            RolloutFailureClass failureClass = RolloutFailureClass.valueOf(className);

            // additionalProperties:false is the redaction backbone — pin it.
            assertThat(variant.path("additionalProperties").asBoolean(true))
                    .as("%s additionalProperties:false", className).isFalse();

            Set<String> schemaRequired = new LinkedHashSet<>();
            variant.path("required").forEach(n -> schemaRequired.add(n.asText()));
            Set<String> schemaProps = new LinkedHashSet<>();
            variant.path("properties").fieldNames().forEachRemaining(schemaProps::add);
            // contract invariant: every property is required (Codex must-fix 3)
            assertThat(schemaProps).as("%s required == properties", className).isEqualTo(schemaRequired);

            var spec = RolloutFailureEvidenceValidator.SPEC.get(failureClass);
            assertThat(spec.keySet())
                    .as("%s validator allowlist == schema required", className)
                    .isEqualTo(schemaRequired);
            // TYPE-aware: the validator's Kind must match the schema property
            // type/$ref/format/nullability — otherwise a field could keep its
            // name but loosen its kind (e.g. redactedString -> STRING) undetected.
            var props = variant.path("properties");
            for (String field : schemaRequired) {
                RolloutFailureEvidenceValidator.Kind expected = kindFor(props.get(field));
                assertThat(spec.get(field))
                        .as("%s.%s kind == schema-derived kind", className, field)
                        .isEqualTo(expected);
            }
        }

        // Every contract class is covered by the validator + the Java enum, and vice versa.
        Set<String> enumClasses = new HashSet<>();
        for (RolloutFailureClass c : RolloutFailureClass.values()) {
            enumClasses.add(c.name());
        }
        assertThat(enumClasses).as("enum == schema failure classes").isEqualTo(schemaClasses);

        // The schema's failureClass + queueState + confidence enums match the Java enums.
        assertThat(stringEnum(schema, "failureClass")).isEqualTo(enumClasses);
        Set<String> states = new HashSet<>();
        for (com.example.endpointadmin.model.RolloutFailureState s
                : com.example.endpointadmin.model.RolloutFailureState.values()) {
            states.add(s.wire());
        }
        assertThat(stringEnum(schema, "queueState")).isEqualTo(states);
        Set<String> confidences = new HashSet<>();
        for (com.example.endpointadmin.model.ClassificationConfidence c
                : com.example.endpointadmin.model.ClassificationConfidence.values()) {
            confidences.add(c.wire());
        }
        assertThat(stringEnum(schema, "confidence")).isEqualTo(confidences);
    }

    private static RolloutFailureEvidenceValidator.Kind kindFor(JsonNode prop) {
        if (prop.has("const")) {
            return RolloutFailureEvidenceValidator.Kind.CLASS_CONST;
        }
        if (prop.has("$ref")) {
            String ref = prop.path("$ref").asText();
            if (ref.endsWith("hashHex")) {
                return RolloutFailureEvidenceValidator.Kind.HASH_HEX;
            }
            if (ref.endsWith("nullableHashHex")) {
                return RolloutFailureEvidenceValidator.Kind.NULLABLE_HASH_HEX;
            }
            if (ref.endsWith("uuid")) {
                return RolloutFailureEvidenceValidator.Kind.UUID;
            }
            if (ref.endsWith("redactedString")) {
                return RolloutFailureEvidenceValidator.Kind.REDACTED_STRING;
            }
            throw new IllegalStateException("unmapped $ref " + ref);
        }
        boolean nullable = false;
        JsonNode type = prop.path("type");
        String base = null;
        if (type.isArray()) {
            for (JsonNode t : type) {
                if ("null".equals(t.asText())) {
                    nullable = true;
                } else {
                    base = t.asText();
                }
            }
        } else {
            base = type.asText();
        }
        boolean dateTime = "date-time".equals(prop.path("format").asText());
        if ("integer".equals(base)) {
            return RolloutFailureEvidenceValidator.Kind.INTEGER;
        }
        if ("string".equals(base)) {
            if (dateTime) {
                return nullable
                        ? RolloutFailureEvidenceValidator.Kind.NULLABLE_DATE_TIME
                        : RolloutFailureEvidenceValidator.Kind.DATE_TIME;
            }
            return nullable
                    ? RolloutFailureEvidenceValidator.Kind.NULLABLE_STRING
                    : RolloutFailureEvidenceValidator.Kind.STRING;
        }
        throw new IllegalStateException("unmapped property type " + type);
    }

    private static Set<String> stringEnum(JsonNode schema, String def) {
        Set<String> out = new HashSet<>();
        schema.path("$defs").path(def).path("enum").forEach(n -> out.add(n.asText()));
        return out;
    }
}
