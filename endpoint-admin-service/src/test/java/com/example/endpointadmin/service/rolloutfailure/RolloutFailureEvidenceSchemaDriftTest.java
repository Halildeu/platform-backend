package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zero-drift guard: the code-defined validator registry MUST stay congruent with
 * the committed contract schema (failed-device-queue.schema.json). Type-aware —
 * it maps each schema property's kind/$ref/format/nullability to the validator
 * {@code Kind}, so a field that keeps its name but loosens its kind (e.g.
 * redactedString → string) is caught, not just a missing/extra field.
 */
class RolloutFailureEvidenceSchemaDriftTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonNode schema() throws IOException {
        // repo-root relative (module cwd is endpoint-admin-service)
        Path p = Path.of("..", "docs", "contracts", "faz-22-failed-device-queue",
                "failed-device-queue.schema.json");
        return MAPPER.readTree(Files.readString(p));
    }

    @Test
    void validatorRegistryMatchesContractSchema() throws IOException {
        JsonNode schema = schema();
        JsonNode oneOf = schema.path("$defs").path("evidence").path("oneOf");
        assertThat(oneOf.isArray()).as("evidence.oneOf present").isTrue();
        assertThat(oneOf.size()).isEqualTo(RolloutFailureClass.values().length);

        for (JsonNode variant : oneOf) {
            assertThat(variant.path("additionalProperties").asBoolean(true))
                    .as("each evidence variant is additionalProperties:false").isFalse();
            String className = variant.path("properties").path("class").path("const").asText();
            RolloutFailureClass failureClass = RolloutFailureClass.valueOf(className);

            Set<String> schemaRequired = new HashSet<>();
            variant.path("required").forEach(n -> schemaRequired.add(n.asText()));
            Set<String> schemaProps = new HashSet<>();
            variant.path("properties").fieldNames().forEachRemaining(schemaProps::add);
            assertThat(schemaRequired).as("%s required == properties", className).isEqualTo(schemaProps);

            var spec = RolloutFailureEvidenceValidator.SPEC.get(failureClass);
            assertThat(spec.keySet()).as("%s validator allowlist == schema required", className)
                    .isEqualTo(schemaRequired);
            JsonNode props = variant.path("properties");
            for (String field : schemaRequired) {
                assertThat(spec.get(field))
                        .as("%s.%s kind == schema-derived kind", className, field)
                        .isEqualTo(kindFor(props.get(field)));
            }
        }
    }

    @Test
    void enumSetsMatchJavaEnums() throws IOException {
        JsonNode defs = schema().path("$defs");
        assertThat(stringEnum(defs, "failureClass")).isEqualTo(enumNames(RolloutFailureClass.values()));
        assertThat(stringEnum(defs, "queueState")).isEqualTo(wireOf(RolloutFailureState.values()));
        assertThat(stringEnum(defs, "confidence")).isEqualTo(wireOf(RolloutClassificationConfidence.values()));
    }

    private static RolloutFailureEvidenceValidator.Kind kindFor(JsonNode prop) {
        if (prop.has("const")) {
            return RolloutFailureEvidenceValidator.Kind.CLASS_CONST;
        }
        if (prop.has("$ref")) {
            String ref = prop.path("$ref").asText();
            if (ref.endsWith("nullableHashHex")) {
                return RolloutFailureEvidenceValidator.Kind.NULLABLE_HASH_HEX;
            }
            if (ref.endsWith("hashHex")) {
                return RolloutFailureEvidenceValidator.Kind.HASH_HEX;
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
        String base = null;
        JsonNode type = prop.path("type");
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

    private static Set<String> stringEnum(JsonNode defs, String def) {
        Set<String> s = new HashSet<>();
        defs.path(def).path("enum").forEach(n -> s.add(n.asText()));
        return s;
    }

    private static Set<String> enumNames(Enum<?>[] values) {
        Set<String> s = new HashSet<>();
        for (Enum<?> v : values) {
            s.add(v.name());
        }
        return s;
    }

    private static Set<String> wireOf(RolloutFailureState[] values) {
        Set<String> s = new HashSet<>();
        for (RolloutFailureState v : values) {
            s.add(v.wire());
        }
        return s;
    }

    private static Set<String> wireOf(RolloutClassificationConfidence[] values) {
        Set<String> s = new HashSet<>();
        for (RolloutClassificationConfidence v : values) {
            s.add(v.wire());
        }
        return s;
    }
}
