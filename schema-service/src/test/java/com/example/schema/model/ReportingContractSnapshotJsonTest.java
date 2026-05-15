package com.example.schema.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Adım 12 — wire-contract guard for {@link ReportingContractSnapshot}.
 *
 * <p>The etl-worker Python consumer
 * ({@code etl_worker/schema_service_client.py}) fails closed on the
 * exact snake_case keys {@code contract_version}, {@code allowlist_name},
 * {@code allowlist_version}, {@code tables}, and per-column {@code type}.
 * A Java record serialises field names verbatim by default, so the
 * {@code @JsonNaming(SnakeCaseStrategy)} on {@link ReportingContractSnapshot}
 * is load-bearing. This test serialises a sample contract and asserts the
 * JSON keys so an accidental annotation removal is caught at build time.
 */
class ReportingContractSnapshotJsonTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void serialises_to_snake_case_wire_keys() throws Exception {
        ReportingContractSnapshot contract = new ReportingContractSnapshot(
            "1",
            "ReportingAllowlist",
            "V1",
            List.of(new ReportingContractTable("dbo", "INVOICE", List.of(
                new ReportingContractColumn("ID", "int", false),
                new ReportingContractColumn("AMOUNT", "decimal(18,2)", true)
            )))
        );

        String json = MAPPER.writeValueAsString(contract);

        // Root provenance keys — these are the etl-worker required fields.
        assertThat(json).contains("\"contract_version\":\"1\"");
        assertThat(json).contains("\"allowlist_name\":\"ReportingAllowlist\"");
        assertThat(json).contains("\"allowlist_version\":\"V1\"");
        assertThat(json).contains("\"tables\":[");

        // camelCase leak guard — the Java field names must NOT surface.
        assertThat(json).doesNotContain("contractVersion");
        assertThat(json).doesNotContain("allowlistName");
        assertThat(json).doesNotContain("allowlistVersion");

        // Table + column keys (single-word fields — snake_case is a no-op).
        assertThat(json).contains("\"schema\":\"dbo\"");
        assertThat(json).contains("\"name\":\"INVOICE\"");
        assertThat(json).contains("\"columns\":[");
        assertThat(json).contains("\"name\":\"ID\"");
        assertThat(json).contains("\"type\":\"int\"");
        assertThat(json).contains("\"nullable\":false");
        // Legacy column field name must NOT appear in the new contract.
        assertThat(json).doesNotContain("dataType");
    }

    @Test
    void round_trips_through_jackson() throws Exception {
        ReportingContractSnapshot original = new ReportingContractSnapshot(
            "1", "ReportingAllowlist", "V1",
            List.of(new ReportingContractTable("dbo", "ORDERS", List.of(
                new ReportingContractColumn("ID", "bigint", false)
            )))
        );

        String json = MAPPER.writeValueAsString(original);
        ReportingContractSnapshot parsed =
            MAPPER.readValue(json, ReportingContractSnapshot.class);

        assertThat(parsed).isEqualTo(original);
    }
}
