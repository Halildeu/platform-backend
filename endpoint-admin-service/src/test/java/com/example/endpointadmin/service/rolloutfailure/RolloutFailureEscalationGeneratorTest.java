package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureItemResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** §9.4 escalation body generation from a queue item. */
class RolloutFailureEscalationGeneratorTest {

    private final RolloutFailureEscalationGenerator generator =
            new RolloutFailureEscalationGenerator(new ObjectMapper());

    private RolloutFailureItemResponse item(String failureClass, Map<String, Object> evidence) {
        UUID id = UUID.randomUUID();
        UUID device = UUID.randomUUID();
        return new RolloutFailureItemResponse(id, "rollout-2026-q3", "wave-02-pilot-50", device,
                failureClass, "new", 0, 3, Instant.now(), Instant.now(), Instant.now(),
                evidence, "endpoint-packaging", Boolean.FALSE, null, null, null, null, null,
                null, "high", "auto:command-result:v1", 0L);
    }

    @Test
    void rendersAllTemplateFieldsAndRedactedEvidence() {
        RolloutFailureItemResponse i = item("INSTALLER_MSI",
                Map.of("class", "INSTALLER_MSI", "msi_exit_code", 1627));
        RolloutFailureEscalationResponse r = generator.generate(i);

        assertThat(r.issueTitle()).isEqualTo("Rollout Failure Escalation — INSTALLER_MSI / wave-02-pilot-50");
        assertThat(r.issueBody())
                .contains("**failure_id:** `" + i.id() + "`")
                .contains("rollout-2026-q3")
                .contains("**failure class:** `INSTALLER_MSI`")
                .contains("**current state:** `new`  (retry 0/3)")
                .contains("**first-action owner:** `endpoint-packaging`")
                .contains("`high` (`auto:command-result:v1`)")
                .contains("msi_exit_code")                 // the redacted evidence is embedded
                .contains("canonical state is the backend queue item") // provenance note
                .contains("Re-run the MSI install")          // per-class requested action
                .contains("Definition of done")              // full template tail
                .contains("Close this issue ONLY after the backend queue item reaches resolved/waived")
                .contains("Backend queue item transitioned to `resolved` or `waived`")
                .contains("`stop_line_status` re-evaluated");
        assertThat(r.labels()).contains("rollout-failure", "class:INSTALLER_MSI",
                "state:new", "wave:wave-02-pilot-50");
        assertThat(r.failureId()).isEqualTo(i.id());
    }

    @Test
    void perClassRequestedActionVaries() {
        assertThat(generator.generate(item("DNS_EDGE_MTLS", Map.of())).issueBody())
                .contains("edge DNS + mTLS reachability");
        assertThat(generator.generate(item("EDR_NETWORK", Map.of())).issueBody())
                .contains("EDR / firewall block");
    }

    @Test
    void embedsOnlyTheAlreadyRedactedEvidenceVerbatim() {
        // the generator does not invent fields — the body's JSON block is exactly
        // the (already-redacted) evidence map it was given.
        RolloutFailureEscalationResponse r = generator.generate(
                item("DNS_EDGE_MTLS", Map.of("endpoint_host_hash", "deadbeef")));
        assertThat(r.issueBody()).contains("endpoint_host_hash").contains("deadbeef");
    }
}
