package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed, schema-bound validator for the per-class redacted evidence object
 * (Faz 22.5 #527 slice-1b write path; contract §3/§7; Codex 019eaaf0). #528's
 * read foundation stores {@code evidence_redacted_json} as an unvalidated Map —
 * this validator is the missing write-side redaction control: it runs before any
 * persistence so a raw value can never reach the JSONB column.
 *
 * <p>The backend has no JSON-schema library and enforces redaction through typed
 * allowlists everywhere, so this uses a code-defined per-class field-kind
 * registry ({@code SPEC}) pinned to the committed contract schema
 * ({@code failed-device-queue.schema.json $defs.evidence.oneOf}) by
 * {@code RolloutFailureEvidenceSchemaDriftTest} (type-aware: kind, not just
 * field set).
 *
 * <p>Guarantees (fail-closed → caller maps to HTTP 400):
 * key set EQUALS the per-class allowlist (additionalProperties:false + every
 * field required); the {@code class} discriminator matches; each field matches
 * its kind; NO string leaf carries a secret/PII marker (JWT/PEM/bearer/auth/
 * password/full-SID/email-UPN/IPv4/IPv6). Returns a canonical re-serialized
 * object so the persisted jsonb never carries the raw request map.
 */
@Component
public class RolloutFailureEvidenceValidator {

    public enum Kind {
        CLASS_CONST, HASH_HEX, NULLABLE_HASH_HEX, UUID, STRING, NULLABLE_STRING,
        INTEGER, DATE_TIME, NULLABLE_DATE_TIME, REDACTED_STRING
    }

    private static final Pattern HASH_HEX = Pattern.compile("^[0-9a-f]{8,64}$");
    private static final Pattern UUID_PAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern REDACTED = Pattern.compile("^(\\[redacted|redacted:)");
    // Unambiguous secret / PII leak markers (contract §3/§7).
    private static final Pattern[] FORBIDDEN_RAW = {
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}"),                 // JWT
            Pattern.compile("-----BEGIN "),                          // PEM block
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._-]{8,}"),  // bearer token
            Pattern.compile("(?i)\\bauthorization\\s*:"),            // raw auth header
            Pattern.compile("(?i)password\\s*[=:]"),                 // password=
            Pattern.compile("S-1-5-21(-\\d+){3}-\\d+"),              // full Windows SID
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), // email / UPN
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),     // raw IPv4
            Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{0,4}\\b") // raw IPv6 (host:port has one colon)
    };

    static final Map<RolloutFailureClass, Map<String, Kind>> SPEC = buildSpec();

    private final ObjectMapper objectMapper;

    public RolloutFailureEvidenceValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** @return a canonical, validated evidence object. @throws InvalidEvidence on any violation. */
    public JsonNode validate(RolloutFailureClass failureClass, JsonNode evidence) {
        Map<String, Kind> spec = SPEC.get(failureClass);
        if (spec == null) {
            throw new InvalidEvidence("unknown failure class " + failureClass);
        }
        if (evidence == null || !evidence.isObject()) {
            throw new InvalidEvidence("evidence must be a JSON object");
        }
        java.util.Set<String> present = new java.util.HashSet<>();
        evidence.fieldNames().forEachRemaining(present::add);
        if (!present.equals(spec.keySet())) {
            throw new InvalidEvidence("evidence keys " + present + " != required allowlist "
                    + spec.keySet() + " for class " + failureClass);
        }
        ObjectNode canonical = objectMapper.createObjectNode();
        for (Map.Entry<String, Kind> e : spec.entrySet()) {
            JsonNode v = evidence.get(e.getKey());
            validateField(failureClass, e.getKey(), e.getValue(), v);
            canonical.set(e.getKey(), v);
        }
        return canonical;
    }

    /** Validate + return the canonical evidence as a Map (the entity column type). */
    public Map<String, Object> validateToMap(RolloutFailureClass failureClass, JsonNode evidence) {
        JsonNode canonical = validate(failureClass, evidence);
        return objectMapper.convertValue(canonical,
                new com.fasterxml.jackson.core.type.TypeReference<LinkedHashMap<String, Object>>() {});
    }

    private void validateField(RolloutFailureClass failureClass, String field, Kind kind, JsonNode v) {
        boolean isNull = v == null || v.isNull();
        switch (kind) {
            case CLASS_CONST -> {
                if (!v.isTextual() || !failureClass.name().equals(v.asText())) {
                    throw new InvalidEvidence("class must be " + failureClass.name());
                }
            }
            case HASH_HEX -> requireHex(field, v);
            case NULLABLE_HASH_HEX -> { if (!isNull) { requireHex(field, v); } }
            case UUID -> {
                if (!v.isTextual() || !UUID_PAT.matcher(v.asText()).matches()) {
                    throw new InvalidEvidence(field + " must be a uuid");
                }
            }
            case STRING -> { requireString(field, v); scanRaw(field, v.asText()); }
            case NULLABLE_STRING -> { if (!isNull) { requireString(field, v); scanRaw(field, v.asText()); } }
            case INTEGER -> {
                if (!v.isIntegralNumber()) {
                    throw new InvalidEvidence(field + " must be an integer");
                }
            }
            case DATE_TIME -> requireDateTime(field, v);
            case NULLABLE_DATE_TIME -> { if (!isNull) { requireDateTime(field, v); } }
            case REDACTED_STRING -> {
                if (!isNull) {
                    if (!v.isTextual() || !REDACTED.matcher(v.asText()).find()) {
                        throw new InvalidEvidence(field + " must be null or a redaction marker");
                    }
                    scanRaw(field, v.asText());
                }
            }
        }
    }

    private void requireHex(String field, JsonNode v) {
        if (v == null || v.isNull() || !v.isTextual() || !HASH_HEX.matcher(v.asText()).matches()) {
            throw new InvalidEvidence(field + " must match ^[0-9a-f]{8,64}$");
        }
    }

    private void requireString(String field, JsonNode v) {
        if (!v.isTextual()) {
            throw new InvalidEvidence(field + " must be a string");
        }
    }

    private void requireDateTime(String field, JsonNode v) {
        if (v == null || v.isNull() || !v.isTextual()) {
            throw new InvalidEvidence(field + " must be a date-time string");
        }
        try {
            java.time.OffsetDateTime.parse(v.asText());
        } catch (RuntimeException ex) {
            throw new InvalidEvidence(field + " is not a valid ISO-8601 date-time");
        }
    }

    private void scanRaw(String field, String value) {
        for (Pattern p : FORBIDDEN_RAW) {
            if (p.matcher(value).find()) {
                throw new InvalidEvidence(field + " contains a forbidden raw secret/PII marker");
            }
        }
    }

    private static Map<RolloutFailureClass, Map<String, Kind>> buildSpec() {
        Map<RolloutFailureClass, Map<String, Kind>> m = new LinkedHashMap<>();
        m.put(RolloutFailureClass.DNS_EDGE_MTLS, spec(
                "class", Kind.CLASS_CONST, "endpoint_host_hash", Kind.HASH_HEX,
                "edge_target", Kind.STRING, "dns_error_code", Kind.NULLABLE_STRING,
                "tls_alert", Kind.NULLABLE_STRING, "mtls_peer_cert_fingerprint_prefix", Kind.NULLABLE_HASH_HEX,
                "observed_at", Kind.DATE_TIME, "source", Kind.NULLABLE_STRING));
        m.put(RolloutFailureClass.CERT_IDENTITY, spec(
                "class", Kind.CLASS_CONST, "device_id", Kind.UUID,
                "cert_fingerprint_prefix", Kind.NULLABLE_HASH_HEX, "issuer_id", Kind.NULLABLE_STRING,
                "subject_san_hash", Kind.NULLABLE_HASH_HEX, "enrollment_status", Kind.STRING,
                "cert_not_before", Kind.NULLABLE_DATE_TIME, "cert_not_after", Kind.NULLABLE_DATE_TIME,
                "audit_event_id", Kind.NULLABLE_STRING));
        m.put(RolloutFailureClass.INSTALLER_MSI, spec(
                "class", Kind.CLASS_CONST, "device_id", Kind.UUID,
                "msi_product_code", Kind.NULLABLE_STRING, "msi_exit_code", Kind.INTEGER,
                "agent_version", Kind.NULLABLE_STRING, "installer_phase", Kind.NULLABLE_STRING,
                "log_excerpt_redacted", Kind.REDACTED_STRING, "gpo_assignment_id", Kind.NULLABLE_STRING));
        m.put(RolloutFailureClass.SERVICE_HMAC_MODE, spec(
                "class", Kind.CLASS_CONST, "device_id", Kind.UUID,
                "service_state", Kind.STRING, "agent_mode", Kind.STRING,
                "hmac_error_code", Kind.NULLABLE_STRING, "last_heartbeat_at", Kind.NULLABLE_DATE_TIME,
                "command_id", Kind.NULLABLE_STRING, "agent_version", Kind.NULLABLE_STRING));
        m.put(RolloutFailureClass.BACKEND_RESULT_SUBMIT, spec(
                "class", Kind.CLASS_CONST, "device_id", Kind.UUID,
                "command_id", Kind.NULLABLE_STRING, "result_submit_http_status", Kind.INTEGER,
                "backend_error_code", Kind.NULLABLE_STRING, "request_id", Kind.NULLABLE_STRING,
                "received_at", Kind.NULLABLE_DATE_TIME, "idempotency_key_hash", Kind.NULLABLE_HASH_HEX));
        m.put(RolloutFailureClass.EDR_NETWORK, spec(
                "class", Kind.CLASS_CONST, "device_id", Kind.UUID,
                "network_segment_id", Kind.NULLABLE_STRING, "edr_vendor", Kind.STRING,
                "blocked_process_hash_prefix", Kind.NULLABLE_HASH_HEX, "blocked_destination", Kind.NULLABLE_STRING,
                "firewall_rule_id", Kind.NULLABLE_STRING, "last_successful_contact_at", Kind.NULLABLE_DATE_TIME));
        return Map.copyOf(m);
    }

    private static Map<String, Kind> spec(Object... pairs) {
        LinkedHashMap<String, Kind> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            m.put((String) pairs[i], (Kind) pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Thrown on any evidence violation; the service maps it to HTTP 400. */
    public static class InvalidEvidence extends RuntimeException {
        public InvalidEvidence(String message) {
            super(message);
        }
    }
}
