package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.RolloutClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Evidence-first classifier for command-result-driven rollout failures (Faz 22.5
 * #527 §9.2 slice-2a; Codex 019eaaf0). It maps a FAILED/PARTIAL/UNSUPPORTED agent
 * command result onto ONE of the three command-result failure classes
 * (INSTALLER_MSI / SERVICE_HMAC_MODE / BACKEND_RESULT_SUBMIT) — and ONLY if it
 * can build SCHEMA-VALID, TRUTHFUL evidence from the result's already-redacted
 * fields. If a required evidence field can't be truthfully filled (no MSI exit
 * code, no service_state/agent_mode, no result-submit HTTP status), it SKIPS —
 * it never fabricates a sentinel or forces a class. The other three classes
 * (DNS_EDGE_MTLS / CERT_IDENTITY / EDR_NETWORK) come from heartbeat/enrollment/
 * edge signals and are deferred to slice-2b.
 */
@Component
public class RolloutFailureClassifier {

    /** Closed allowlist for a bounded error/backend code reused as evidence. */
    private static final Pattern CODE = Pattern.compile("^[A-Z][A-Z0-9_:-]{2,127}$");
    /** An MSI failure signal must be present in the error code — a bare non-zero
     *  exit code is NOT MSI evidence (the installer may be WINGET/EXE). "MSI" as a
     *  token (underscores/digits/boundaries around it; not a substring of a word). */
    private static final Pattern MSI_MARKER = Pattern.compile("(?i)(?<![A-Za-z])MSI(?![A-Za-z])");
    /** Parse an MSI exit code embedded in an error code, e.g. INSTALL_FAILED_MSI_1627. */
    private static final Pattern MSI_CODE = Pattern.compile("(?:MSI|EXIT)[_-]?(\\d{1,5})\\b");
    /** A service/HMAC/mode failure must carry a distinctive HMAC|MODE signal in the
     *  error code — service_state+agent_mode being present is not enough, and would
     *  otherwise mislabel a BACKEND_RESULT_SUBMIT result that happens to carry agent
     *  details (classify order is MSI -> HMAC -> BACKEND). */
    private static final Pattern HMAC_MARKER = Pattern.compile("(?i)(?<![A-Za-z])(HMAC|MODE)(?![A-Za-z])");
    /** Parse a result-submit / backend HTTP status, e.g. RESULT_SUBMIT_409 / BACKEND_500. */
    private static final Pattern HTTP_CODE = Pattern.compile("(?:RESULT_SUBMIT|BACKEND|HTTP)[_-]?(\\d{3})\\b");
    private static final String REDACTED_PRESENT = "[redacted: command_result_error_message_present]";

    private final ObjectMapper objectMapper;

    public RolloutFailureClassifier(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** A class + confidence + the per-class evidence object (pre-validation). */
    public record Classified(RolloutFailureClass failureClass,
                             RolloutClassificationConfidence confidence,
                             JsonNode evidence) {
    }

    /** The command-result signal the classifier reasons over (already redacted upstream). */
    public record Signal(UUID deviceId, UUID commandId, CommandType commandType,
                         String errorCode, Integer exitCode, boolean errorMessagePresent,
                         Map<String, Object> resultPayload) {
    }

    public Optional<Classified> classify(Signal s) {
        Optional<Classified> msi = tryInstallerMsi(s);
        if (msi.isPresent()) {
            return msi;
        }
        Optional<Classified> hmac = tryServiceHmacMode(s);
        if (hmac.isPresent()) {
            return hmac;
        }
        return tryBackendResultSubmit(s);
    }

    private Optional<Classified> tryInstallerMsi(Signal s) {
        if (s.commandType() != CommandType.INSTALL_SOFTWARE
                && s.commandType() != CommandType.UNINSTALL_SOFTWARE) {
            return Optional.empty();
        }
        if (s.errorCode() == null || !MSI_MARKER.matcher(s.errorCode()).find()) {
            return Optional.empty(); // no MSI failure signal → not an MSI failure (could be WINGET/EXE)
        }
        Integer exit = s.exitCode();
        RolloutClassificationConfidence confidence = RolloutClassificationConfidence.HIGH;
        if (exit == null) {
            // Fall back to an MSI/EXIT code embedded in the error code.
            exit = parseInt(MSI_CODE, s.errorCode());
            confidence = RolloutClassificationConfidence.MEDIUM;
        }
        if (exit == null || exit == 0) {
            return Optional.empty(); // no truthful msi_exit_code → skip (no sentinel)
        }
        ObjectNode e = objectMapper.createObjectNode();
        e.put("class", "INSTALLER_MSI");
        e.put("device_id", s.deviceId().toString());
        e.putNull("msi_product_code");
        e.put("msi_exit_code", exit);
        e.putNull("agent_version");
        e.putNull("installer_phase");
        if (s.errorMessagePresent()) {
            e.put("log_excerpt_redacted", REDACTED_PRESENT);
        } else {
            e.putNull("log_excerpt_redacted");
        }
        e.putNull("gpo_assignment_id");
        return Optional.of(new Classified(RolloutFailureClass.INSTALLER_MSI, confidence, e));
    }

    private Optional<Classified> tryServiceHmacMode(Signal s) {
        // Require a distinctive HMAC|MODE signal in the error code — service_state +
        // agent_mode being present is not enough (a BACKEND_RESULT_SUBMIT result can
        // also carry agent details, and HMAC is tried before BACKEND).
        if (s.errorCode() == null || !HMAC_MARKER.matcher(s.errorCode()).find()) {
            return Optional.empty();
        }
        // service_state + agent_mode are REQUIRED non-null strings — only emit if
        // the result payload carries truthful values (never fabricate). submitResult
        // nests the agent details under result_payload.details, so unwrap that first
        // (with a top-level fallback).
        Map<String, Object> details = unwrapDetails(s.resultPayload());
        String serviceState = payloadString(details, "service_state");
        String agentMode = payloadString(details, "agent_mode");
        if (serviceState == null || agentMode == null) {
            return Optional.empty();
        }
        ObjectNode e = objectMapper.createObjectNode();
        e.put("class", "SERVICE_HMAC_MODE");
        e.put("device_id", s.deviceId().toString());
        e.put("service_state", serviceState);
        e.put("agent_mode", agentMode);
        e.put("hmac_error_code", allowlistedCodeOrNull(s.errorCode()));
        e.putNull("last_heartbeat_at");
        e.put("command_id", s.commandId() == null ? null : s.commandId().toString());
        e.putNull("agent_version");
        return Optional.of(new Classified(RolloutFailureClass.SERVICE_HMAC_MODE,
                RolloutClassificationConfidence.HIGH, e));
    }

    private Optional<Classified> tryBackendResultSubmit(Signal s) {
        Integer http = parseInt(HTTP_CODE, s.errorCode());
        if (http == null) {
            return Optional.empty(); // no truthful result_submit_http_status → skip
        }
        ObjectNode e = objectMapper.createObjectNode();
        e.put("class", "BACKEND_RESULT_SUBMIT");
        e.put("device_id", s.deviceId().toString());
        e.put("command_id", s.commandId() == null ? null : s.commandId().toString());
        e.put("result_submit_http_status", http);
        e.put("backend_error_code", allowlistedCodeOrNull(s.errorCode()));
        e.putNull("request_id");
        e.putNull("received_at");
        e.putNull("idempotency_key_hash");
        return Optional.of(new Classified(RolloutFailureClass.BACKEND_RESULT_SUBMIT,
                RolloutClassificationConfidence.MEDIUM, e));
    }

    private static Integer parseInt(Pattern p, String s) {
        if (s == null) {
            return null;
        }
        Matcher m = p.matcher(s);
        if (m.find()) {
            try {
                return Integer.valueOf(m.group(1));
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String allowlistedCodeOrNull(String code) {
        if (code == null) {
            return null;
        }
        String trimmed = code.trim();
        return CODE.matcher(trimmed).matches() ? trimmed : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> unwrapDetails(Map<String, Object> payload) {
        if (payload != null && payload.get("details") instanceof Map<?, ?> details) {
            return (Map<String, Object>) details;
        }
        return payload; // backward-compatible top-level fallback
    }

    private static String payloadString(Map<String, Object> payload, String key) {
        if (payload == null) {
            return null;
        }
        Object v = payload.get(key);
        if (v instanceof String str && !str.isBlank()) {
            return str;
        }
        return null;
    }
}
