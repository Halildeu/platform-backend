package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import com.example.ethics.model.AuditOutbox;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.CaseEscalationRepository;
import com.example.ethics.service.EscalationSweeper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * Faz 35 ES-301 — the escalation row and its audit event are one transaction (#882).
 *
 * <p>If the audit outbox write fails, the escalation row must not exist either: a level on
 * file with no ledger entry would be an escalation the organisation could later deny having
 * been told about. Separate class because the spy changes the context.
 */
@SpringBootTest(properties = {
        "ethics.sla.escalation.enabled=true",
        "ethics.sla.escalation.steps=PT0S"})
@ActiveProfiles("test")
class EscalationRollbackTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000035");
    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Autowired EscalationSweeper sweeper;
    @Autowired CaseEscalationRepository escalations;
    @Autowired JdbcTemplate jdbc;
    @MockitoSpyBean AuditOutboxRepository auditOutbox;

    @Test
    @DisplayName("denetim yazımı başarısızsa eskalasyon satırı da yazılmaz")
    void anAuditWriteFailureRollsTheEscalationBack() {
        UUID caseId = UUID.randomUUID();
        Instant createdAt = NOW.minus(Duration.ofDays(12));
        jdbc.update("INSERT INTO ethics_cases (id, org_id, product_id, status, version, created_at, updated_at)"
                        + " VALUES (?, ?, 'etik-speak', 'NEW', 0, ?, ?)",
                caseId, ORG, Timestamp.from(createdAt), Timestamp.from(createdAt));
        doThrow(new IllegalStateException("ledger unavailable"))
                .when(auditOutbox).save(argThat((AuditOutbox row) ->
                        row != null && EscalationSweeper.EVENT_TYPE.equals(row.getEventType())));

        var result = sweeper.runCycle(NOW);

        assertThat(result.failed()).isGreaterThanOrEqualTo(1);
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId))
                .as("denetim olayı yazılamadıysa seviye de kayda geçmemeli")
                .isEmpty();
    }
}
