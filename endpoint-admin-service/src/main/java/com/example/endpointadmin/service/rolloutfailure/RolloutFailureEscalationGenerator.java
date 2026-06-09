package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureItemResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Auto-generates the GitHub escalation issue body from a queue item (Faz 22.5
 * #527 §9.4) — what the contract template (github-escalation-issue-template.md)
 * says is filled in MANUALLY until §9.4 lands. This GENERATES (preview) the body;
 * the live issue creation (POST to GitHub) is an operator-configured integration
 * and is NOT done here. The body embeds ONLY the already-redacted evidence (the
 * write/ingest validator guarantees no raw logs/tokens/cert/SID/UPN/IP); the
 * generator adds no raw fields, and states explicitly that the CANONICAL state is
 * the backend queue item, not the issue.
 */
@Component
public class RolloutFailureEscalationGenerator {

    private final ObjectMapper objectMapper;

    public RolloutFailureEscalationGenerator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public RolloutFailureEscalationResponse generate(RolloutFailureItemResponse item) {
        String title = "Rollout Failure Escalation — " + item.failureClass() + " / " + item.waveId();
        String evidenceJson;
        try {
            evidenceJson = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(item.evidenceRedacted());
        } catch (JsonProcessingException e) {
            evidenceJson = "{}";
        }

        String body = """
                > **Generated from the backend failed-device queue (`endpoint_rollout_failure`).**
                > **The canonical state is the backend queue item — NOT this issue.** This issue
                > is an escalation *projection*; update state via the backend queue, this issue mirrors it.

                - **failure_id:** `%s`
                - **rollout_id / wave_id:** `%s` / `%s`
                - **device_id:** `%s`
                - **failure class:** `%s`
                - **current state:** `%s`  (retry %d/%d)
                - **first-action owner:** `%s`
                - **classification:** `%s` (`%s`)
                - **stop-line contribution:** `%s`

                ## Redacted evidence

                > Allowlisted fields only (contract §3/§7). No raw logs, tokens, cert PEM, full SID/UPN, or raw IP.

                ```json
                %s
                ```

                ## Requested first action

                %s

                <!--
                Labels (projection, not canonical):
                  rollout-failure, class:%s, state:%s, wave:%s
                Close this issue ONLY after the backend queue item reaches resolved/waived.
                -->

                ## Definition of done

                - [ ] Backend queue item transitioned to `resolved` or `waived` (with reason/owner).
                - [ ] If `waived`: `waived_until` set and recorded in the backend item.
                - [ ] Root-cause + fix captured in `resolution_summary` (redacted).
                - [ ] Wave export regenerated; `stop_line_status` re-evaluated.
                """.formatted(
                item.id(), item.rolloutId(), item.waveId(), item.deviceId(),
                item.failureClass(), item.state(), item.retryCount(), item.maxRetries(),
                item.ownerRole(), item.classificationConfidence(), item.classifierVersion(),
                String.valueOf(item.stopLineContribution()),
                evidenceJson, requestedAction(item.failureClass()),
                item.failureClass(), item.state(), item.waveId());

        List<String> labels = List.of(
                "rollout-failure",
                "class:" + item.failureClass(),
                "state:" + item.state(),
                "wave:" + item.waveId());

        return new RolloutFailureEscalationResponse(title, body, labels, item.id());
    }

    private static String requestedAction(String failureClass) {
        return switch (failureClass) {
            case "DNS_EDGE_MTLS" ->
                    "Verify edge DNS + mTLS reachability for the device's network segment; check the edge target and peer-cert fingerprint.";
            case "CERT_IDENTITY" ->
                    "Re-verify the device enrollment certificate identity (issuer / SAN); re-issue if expired or mismatched.";
            case "INSTALLER_MSI" ->
                    "Re-run the MSI install with verbose logging and investigate the MSI exit code.";
            case "SERVICE_HMAC_MODE" ->
                    "Inspect the agent service state and HMAC/mode; restart the service or re-seed the HMAC credentials.";
            case "BACKEND_RESULT_SUBMIT" ->
                    "Investigate the result-submit HTTP failure (backend availability / idempotency) and retry the command.";
            case "EDR_NETWORK" ->
                    "Check the EDR / firewall block for the device and allowlist the agent egress if appropriate.";
            default -> "Investigate the failure for this device and take the class-appropriate first action.";
        };
    }
}
