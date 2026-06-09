package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Typed, schema-bound validator for the per-class redacted evidence object
 * (Faz 22.5 #527 slice-1a, contract §3/§7; Codex 019eaaf0).
 *
 * <p>The backend has no JSON-schema library and enforces redaction through
 * typed allowlists everywhere, so this mirrors that style with a code-defined
 * per-class field-kind registry (the {@code SPEC} below) instead of a generic
 * schema engine. {@code RolloutFailureEvidenceSchemaDriftTest} pins this
 * registry to the committed contract schema
 * ({@code failed-device-queue.schema.json $defs.evidence.oneOf}) so the two can
 * never silently drift.
 *
 * <p>Guarantees (fail-closed → caller maps to HTTP 400):
 * <ul>
 *   <li>evidence is a JSON object whose key set EQUALS the per-class allowlist
 *       (additionalProperties:false + every contract field required);</li>
 *   <li>the {@code class} discriminator matches the declared class;</li>
 *   <li>each field matches its kind (hash hex / uuid / date-time / integer /
 *       redaction-marker string);</li>
 *   <li>NO string leaf carries an unambiguous secret/PII marker (JWT, PEM,
 *       bearer, full SID, Authorization header, password) — the raw-leak
 *       scanner (Codex: free strings must pass a forbidden-raw scan).</li>
 * </ul>
 * It returns a canonical re-serialized object (registry key order) so the
 * persisted jsonb never carries the raw request map.
 */
@Component
public class RolloutFailureEvidenceValidator {

    public enum Kind {
        CLASS_CONST,
        HASH_HEX,
        NULLABLE_HASH_HEX,
        UUID,
        STRING,
        NULLABLE_STRING,
        INTEGER,
        DATE_TIME,
        NULLABLE_DATE_TIME,
        REDACTED_STRING
    }

    private static final Pattern HASH_HEX = Pattern.compile("^[0-9a-f]{8,64}$");
    private static final Pattern UUID_PAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final Pattern REDACTED = Pattern.compile("^(\\[redacted|redacted:)");
    // Unambiguous secret / PII leak markers (case-insensitive where it matters).
    // Contract §3/§7 forbids raw JWT/bearer/PEM/SID/UPN/email/IP in evidence.
    private static final Pattern[] FORBIDDEN_RAW = {
            Pattern.compile("eyJ[A-Za-z0-9_-]{8,}"),                 // JWT
            Pattern.compile("-----BEGIN "),                          // PEM block
            Pattern.compile("(?i)\\bbearer\\s+[A-Za-z0-9._-]{8,}"),  // bearer token
            Pattern.compile("(?i)\\bauthorization\\s*:"),            // raw auth header
            Pattern.compile("(?i)password\\s*[=:]"),                 // password=
            Pattern.compile("S-1-5-21(-\\d+){3}-\\d+"),              // full Windows SID w/ RID
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), // email / UPN
            Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"),     // raw IPv4
            Pattern.compile("\\b(?:[0-9a-fA-F]{1,4}:){2,}[0-9a-fA-F]{0,4}\\b") // raw IPv6 (>=2 colon groups; host:port has one)
    };

    /** class -> (fieldName -> kind), insertion order = canonical output order. */
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
        // Key set EQUALS the allowlist (additionalProperties:false + all required).
        java.util.Set<String> present = new java.util.HashSet<>();
        evidence.fieldNames().forEachRemaining(present::add);
        if (!present.equals(spec.keySet())) {
            throw new InvalidEvidence("evidence keys " + present + " != required allowlist "
                    + spec.keySet() + " for class " + failureClass);
        }
        ObjectNode canonical = objectMapper.createObjectNode();
        for (Map.Entry<String, Kind> e : spec.entrySet()) {
            String field = e.getKey();
            JsonNode v = evidence.get(field);
            validateField(failureClass, field, e.getValue(), v);
            canonical.set(field, v);
        }
        return canonical;
    }

    private void validateField(RolloutFailureClass failureClass, String field, Kind kind, JsonNode v) {
        boolean isNull = v == null || v.isNull();
        switch (kind) {
            case CLASS_CONST -> {
                if (!v.isTextual() || !failureClass.name().equals(v.asText())) {
                    throw new InvalidEvidence("class must be " + failureClass.name());
                }
            }
            case HASH_HEX -> requireHex(field, v, false);
            case NULLABLE_HASH_HEX -> { if (!isNull) { requireHex(field, v, false); } }
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
            case DATE_TIME -> requireDateTime(field, v, false);
            case NULLABLE_DATE_TIME -> { if (!isNull) { requireDateTime(field, v, false); } }
            case REDACTED_STRING -> {
                if (!isNull) {
                    if (!v.isTextual() || !REDACTED.matcher(v.asText()).find()) {
                        throw new InvalidEvidence(field + " must be null or a redaction marker (\"[redacted…\"/\"redacted:…\")");
                    }
                    scanRaw(field, v.asText());
                }
            }
        }
    }

    private void requireHex(String field, JsonNode v, boolean nullable) {
        if (v == null || v.isNull()) {
            if (nullable) {
                return;
            }
            throw new InvalidEvidence(field + " must be a hex hash");
        }
        if (!v.isTextual() || !HASH_HEX.matcher(v.asText()).matches()) {
            throw new InvalidEvidence(field + " must match ^[0-9a-f]{8,64}$");
        }
    }

    private void requireString(String field, JsonNode v) {
        if (!v.isTextual()) {
            throw new InvalidEvidence(field + " must be a string");
        }
    }

    private void requireDateTime(String field, JsonNode v, boolean nullable) {
        if (v == null || v.isNull()) {
            if (nullable) {
                return;
            }
            throw new InvalidEvidence(field + " must be an ISO-8601 date-time");
        }
        if (!v.isTextual()) {
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
                // never echo the offending value (it is a suspected secret/PII)
                throw new InvalidEvidence(field + " contains a forbidden raw secret/PII marker");
            }
        }
    }

    private static Map<RolloutFailureClass, Map<String, Kind>> buildSpec() {
        Map<RolloutFailureClass, Map<String, Kind>> m = new LinkedHashMap<>();

        m.put(RolloutFailureClass.DNS_EDGE_MTLS, spec(
                "class", Kind.CLASS_CONST,
                "endpoint_host_hash", Kind.HASH_HEX,
                "edge_target", Kind.STRING,
                "dns_error_code", Kind.NULLABLE_STRING,
                "tls_alert", Kind.NULLABLE_STRING,
                "mtls_peer_cert_fingerprint_prefix", Kind.NULLABLE_HASH_HEX,
                "observed_at", Kind.DATE_TIME,
                "source", Kind.NULLABLE_STRING));

        m.put(RolloutFailureClass.CERT_IDENTITY, spec(
                "class", Kind.CLASS_CONST,
                "device_id", Kind.UUID,
                "cert_fingerprint_prefix", Kind.NULLABLE_HASH_HEX,
                "issuer_id", Kind.NULLABLE_STRING,
                "subject_san_hash", Kind.NULLABLE_HASH_HEX,
                "enrollment_status", Kind.STRING,
                "cert_not_before", Kind.NULLABLE_DATE_TIME,
                "cert_not_after", Kind.NULLABLE_DATE_TIME,
                "audit_event_id", Kind.NULLABLE_STRING));

        m.put(RolloutFailureClass.INSTALLER_MSI, spec(
                "class", Kind.CLASS_CONST,
                "device_id", Kind.UUID,
                "msi_product_code", Kind.NULLABLE_STRING,
                "msi_exit_code", Kind.INTEGER,
                "agent_version", Kind.NULLABLE_STRING,
                "installer_phase", Kind.NULLABLE_STRING,
                "log_excerpt_redacted", Kind.REDACTED_STRING,
                "gpo_assignment_id", Kind.NULLABLE_STRING));

        m.put(RolloutFailureClass.SERVICE_HMAC_MODE, spec(
                "class", Kind.CLASS_CONST,
                "device_id", Kind.UUID,
                "service_state", Kind.STRING,
                "agent_mode", Kind.STRING,
                "hmac_error_code", Kind.NULLABLE_STRING,
                "last_heartbeat_at", Kind.NULLABLE_DATE_TIME,
                "command_id", Kind.NULLABLE_STRING,
                "agent_version", Kind.NULLABLE_STRING));

        m.put(RolloutFailureClass.BACKEND_RESULT_SUBMIT, spec(
                "class", Kind.CLASS_CONST,
                "device_id", Kind.UUID,
                "command_id", Kind.NULLABLE_STRING,
                "result_submit_http_status", Kind.INTEGER,
                "backend_error_code", Kind.NULLABLE_STRING,
                "request_id", Kind.NULLABLE_STRING,
                "received_at", Kind.NULLABLE_DATE_TIME,
                "idempotency_key_hash", Kind.NULLABLE_HASH_HEX));

        m.put(RolloutFailureClass.EDR_NETWORK, spec(
                "class", Kind.CLASS_CONST,
                "device_id", Kind.UUID,
                "network_segment_id", Kind.NULLABLE_STRING,
                "edr_vendor", Kind.STRING,
                "blocked_process_hash_prefix", Kind.NULLABLE_HASH_HEX,
                "blocked_destination", Kind.NULLABLE_STRING,
                "firewall_rule_id", Kind.NULLABLE_STRING,
                "last_successful_contact_at", Kind.NULLABLE_DATE_TIME));

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
