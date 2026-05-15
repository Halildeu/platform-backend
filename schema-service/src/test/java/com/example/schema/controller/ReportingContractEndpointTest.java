package com.example.schema.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.schema.model.ReportingContractColumn;
import com.example.schema.model.ReportingContractSnapshot;
import com.example.schema.model.ReportingContractTable;
import com.example.schema.service.ReportingContractService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Adım 12 — {@code SchemaController.getReportingContract} auth + fail-closed
 * coverage. Direct controller method invocation (no MockMvc / Spring
 * context) — fast unit test of the auth + 404-empty logic.
 *
 * <p>Auth model differs from {@code /snapshot}: internal-key only, no JWT
 * branch (this endpoint is a migration/ops contract, not a UI surface).
 *
 * <p>Codex {@code 019e2d64} S2 trap: an empty allowlist intersection must
 * be a {@code 404}, never a {@code 200} with empty {@code tables}.
 */
class ReportingContractEndpointTest {

    private SchemaController controller;
    private ReportingContractService reportingContractService;

    private static ReportingContractSnapshot nonEmptyContract() {
        return new ReportingContractSnapshot(
            "1", "ReportingAllowlist", "V1",
            List.of(new ReportingContractTable("dbo", "INVOICE",
                List.of(new ReportingContractColumn("ID", "int", false))))
        );
    }

    private static ReportingContractSnapshot emptyContract() {
        return new ReportingContractSnapshot(
            "1", "ReportingAllowlist", "V1", List.of()
        );
    }

    @BeforeEach
    void setUp() {
        reportingContractService = mock(ReportingContractService.class);
        controller = new SchemaController(
                mock(com.example.schema.service.SchemaExtractService.class),
                mock(com.example.schema.service.SchemaSnapshotService.class),
                mock(com.example.schema.service.SchemaLookupService.class),
                mock(com.example.schema.service.PathFinderService.class),
                mock(com.example.schema.service.SchemaHealthService.class),
                mock(com.example.schema.service.SchemaDriftService.class),
                mock(com.example.schema.service.QuerySuggestionService.class),
                reportingContractService);
        ReflectionTestUtils.setField(controller, "defaultSchema", "workcube_mikrolink");
        ReflectionTestUtils.setField(controller, "cacheTtlMinutes", 60);
    }

    @Test
    void emptyKeyConfigured_passesThrough() {
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "");
        when(reportingContractService.buildContract(anyString()))
                .thenReturn(nonEmptyContract());

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().tables()).hasSize(1);
    }

    @Test
    void configuredKey_correctHeader_returns200() {
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");
        when(reportingContractService.buildContract(anyString()))
                .thenReturn(nonEmptyContract());

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract(null, "vault-secret-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    void configuredKey_wrongHeader_returns401() {
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract(null, "wrong-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void configuredKey_noHeader_returns401_noJwtBranch() {
        // Unlike /snapshot, this endpoint has NO JWT fallback.
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "vault-secret-key");

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void emptyTables_failsClosed_with404() {
        // Codex 019e2d64 S2 trap: empty allowlist intersection → 404,
        // never 200-with-empty-tables.
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "");
        when(reportingContractService.buildContract(anyString()))
                .thenReturn(emptyContract());

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract(null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void schemaParam_overridesDefault() {
        ReflectionTestUtils.setField(controller, "snapshotInternalApiKey", "");
        when(reportingContractService.buildContract("workcube_mikrolink_2025"))
                .thenReturn(nonEmptyContract());

        ResponseEntity<ReportingContractSnapshot> response =
                controller.getReportingContract("workcube_mikrolink_2025", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
