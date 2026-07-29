package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * Faz 35 ES-302 — the shape of the audit scope classification (#884).
 *
 * <p>What this file does NOT test, deliberately: that the backfill covered the whole
 * ledger. Migrations run against an empty schema here, so the backfill inserts nothing and
 * a coverage assertion would pass without ever looking at a row — the kind of green that
 * means nothing. Coverage is asserted by the migration itself on PostgreSQL
 * (V11, which raises if any worm row is left unclassified) and measured on the live cell.
 *
 * <p>What is testable here is the part a backfill cannot check about itself: whether the
 * table can hold a classification that is internally inconsistent. Those constraints are
 * what stop a later writer — a repair script, a future migration — from recording an
 * ATTACHMENT row with no case, or inventing a type nobody handles.
 */
@SpringBootTest
@ActiveProfiles("test")
class EthicsAuditScopeTest {

    @Autowired JdbcTemplate jdbc;

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    /**
     * A worm row to hang a classification on. It has to come with its outbox row: the
     * ledger references the outbox entry it was projected from, so a synthetic worm row
     * with an invented source is rejected before any scope constraint is reached.
     */
    private UUID seedWormRow() {
        UUID id = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
            insert into ethics_audit_outbox
              (id, org_id, aggregate_id, event_type, payload, status, created_at, attempt_count)
            values (?, ?, ?, 'probe.scope', '{}', 'PENDING', now(), 0)
            """, outboxId, ORG, UUID.randomUUID());
        jdbc.update("""
            insert into ethics_worm_audit
              (id, source_outbox_id, org_id, aggregate_id, event_type, payload,
               event_timestamp, ingested_at, prev_hash, entry_hash, entry_hash_alg,
               entry_hash_version)
            values (?, ?, ?, ?, 'probe.scope', '{}', now(), now(), ?, ?, 'SHA-256', 1)
            """, id, outboxId, ORG, UUID.randomUUID(), "0".repeat(64), "0".repeat(64));
        return id;
    }

    private void classify(UUID wormId, String type, UUID rootCase) {
        jdbc.update("""
            insert into ethics_audit_scope
              (worm_audit_id, aggregate_id, aggregate_type, root_case_id, classified_by)
            values (?, ?, ?, ?, 'test')
            """, wormId, UUID.randomUUID(), type, rootCase);
    }

    @Test
    @DisplayName("bir olay hangi vakaya ait olduğunu taşıyabilir")
    void aClassificationRecordsTheRootCase() {
        UUID worm = seedWormRow();
        UUID rootCase = UUID.randomUUID();
        classify(worm, "ATTACHMENT", rootCase);

        assertThat(jdbc.queryForObject(
                "select root_case_id from ethics_audit_scope where worm_audit_id = ?",
                UUID.class, worm))
                .as("kök vaka kaydedilmedi")
                .isEqualTo(rootCase);
    }

    /**
     * The whole point of the table. A type alone answers "this was an attachment event";
     * the erasure claim is case-scoped, so a classified row without a case is a row that
     * cannot answer the only question being asked of it.
     */
    @Test
    @DisplayName("çözümlenmiş bir olay kök vakasız kaydedilemez")
    void aResolvedRowCannotBeStoredWithoutItsCase() {
        UUID worm = seedWormRow();
        assertThatThrownBy(() -> classify(worm, "ATTACHMENT", null))
                .as("kök vakası olmayan ATTACHMENT satırı kabul edildi")
                .isInstanceOf(Exception.class);
    }

    /**
     * And the mirror. `UNRESOLVED` means the parent was already gone; attaching a case to
     * it would be a guess presented as a record.
     */
    @Test
    @DisplayName("çözümlenemeyen bir olaya vaka uydurulamaz")
    void anUnresolvedRowCannotCarryACase() {
        UUID worm = seedWormRow();
        assertThatThrownBy(() -> classify(worm, "UNRESOLVED", UUID.randomUUID()))
                .as("UNRESOLVED satırına kök vaka iliştirilebildi")
                .isInstanceOf(Exception.class);
    }

    /**
     * A type outside the vocabulary reaches every consumer as a value none of them handle.
     * Rejecting it at write time keeps the failure at the writer rather than at whoever
     * later reads an erasure receipt.
     */
    @Test
    @DisplayName("bilinmeyen bir tür yazılamaz")
    void anUnknownTypeIsRefused() {
        UUID worm = seedWormRow();
        assertThatThrownBy(() -> classify(worm, "SOMETHING_ELSE", UUID.randomUUID()))
                .as("sözlükte olmayan tür kabul edildi")
                .isInstanceOf(Exception.class);
    }

    /** One classification per audit row; two would make the receipt ambiguous. */
    @Test
    @DisplayName("bir olay iki kez sınıflandırılamaz")
    void anAuditRowIsClassifiedOnce() {
        UUID worm = seedWormRow();
        classify(worm, "CASE", UUID.randomUUID());
        assertThatThrownBy(() -> classify(worm, "CASE", UUID.randomUUID()))
                .as("aynı olay iki kez sınıflandırılabildi")
                .isInstanceOf(Exception.class);
    }

    /**
     * A classification that describes a row nobody can find is not evidence. The reference
     * is what keeps the scope table anchored to the ledger rather than drifting into a
     * parallel set of claims about rows that may never have existed.
     */
    @Test
    @DisplayName("olmayan bir olay sınıflandırılamaz")
    void aClassificationCannotFloatFreeOfTheLedger() {
        assertThatThrownBy(() -> classify(UUID.randomUUID(), "CASE", UUID.randomUUID()))
                .as("defterde karşılığı olmayan satır sınıflandırılabildi")
                .isInstanceOf(Exception.class);
    }
}
