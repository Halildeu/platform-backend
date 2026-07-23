package com.example.ethics.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.model.AuditOutbox;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EthicsAuditChainTest {
    @Test
    void canonicalHashIsDeterministicAndBindsPreviousEntry() {
        AuditOutbox row = new AuditOutbox(
                UUID.fromString("11111111-1111-1111-1111-111111111111"),
                UUID.fromString("00000000-0000-0000-0000-000000000035"),
                UUID.fromString("22222222-2222-2222-2222-222222222222"),
                "ethics.report.created",
                "{\"category\":\"OTHER\",\"mode\":\"ANONYMOUS\"}",
                Instant.parse("2026-07-24T00:00:00.123456789Z"));

        String genesis = EthicsAuditChain.computeEntryHash(null, row);
        String repeated = EthicsAuditChain.computeEntryHash(null, row);
        String chained = EthicsAuditChain.computeEntryHash(genesis, row);

        assertThat(genesis).isEqualTo(repeated).matches("[0-9a-f]{64}");
        assertThat(chained).matches("[0-9a-f]{64}").isNotEqualTo(genesis);
        assertThat(EthicsAuditChain.canonicalPayload(row))
                .contains("\"event_timestamp\":\"2026-07-24T00:00:00.123456Z\"")
                .doesNotContain("narrative", "accessSecret");
    }
}
