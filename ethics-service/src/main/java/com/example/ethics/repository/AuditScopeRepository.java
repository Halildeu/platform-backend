package com.example.ethics.repository;

import com.example.ethics.model.WormAuditEntry;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Faz 35 ES-302 — classification of ledger rows, at the moment they are written (#884).
 *
 * <p>V10 classified the ledger by backfill and V11 asserted the result was complete. Both ran
 * once. Nothing classified the rows written afterwards, so coverage was complete at 08:15 on
 * the day of the migration and would have decayed from the next case event onward — silently,
 * because the assertion lives in a migration that never runs again. Measured on the test cell
 * before this change: 430 ledger rows, 430 classified, zero written since. The hole was empty
 * only because nothing had happened yet.
 *
 * <p>That matters more than a normal drifting invariant. The mapping cannot be reconstructed
 * after erasure — that is the entire reason the table exists — so an unclassified row is not a
 * gap that can be repaired later. It is a row that permanently cannot answer "was every record
 * of this case erased?".
 *
 * <p>Deliberately entity-free: the table is never loaded as an object, only written once and
 * counted over. The domain type is present because Spring Data requires one.
 *
 * <p><strong>Why the guarantee is not a database constraint.</strong> A deferred constraint
 * trigger on the ledger would make coverage impossible to bypass, and was rejected for one
 * reason: restore. A dump reloads {@code ethics_worm_audit} and {@code ethics_audit_scope} in
 * separate transactions, so the assertion would fire between them and fail the restore — the
 * operation that exists for the worst day. Coverage is written here instead, in the same
 * transaction as the ledger append, and asserted by
 * {@link #countUnclassifiedLedgerRows()} in tests and in the restore drill.
 */
public interface AuditScopeRepository extends Repository<WormAuditEntry, Long> {

    /** Recorded in {@code classified_by}, so a row's provenance stays readable next to the backfill's. */
    String WRITER = "AuditDeliveryService";

    /**
     * Classifies one ledger row by the same derivation the V10 backfill used — deliberately the
     * same shape, so the two writers cannot disagree about what a CASE row is.
     *
     * <p>Here the parents are alive by construction: the classification happens in the
     * transaction that appends the row, which is the earliest moment it can be asked and the
     * only one where the answer is guaranteed.
     */
    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}ethics_audit_scope
                (worm_audit_id, aggregate_id, aggregate_type, root_case_id, classified_at, classified_by)
            SELECT w.id,
                   w.aggregate_id,
                   CASE
                       WHEN c.id IS NOT NULL THEN 'CASE'
                       WHEN e.id IS NOT NULL THEN 'ATTACHMENT'
                       ELSE 'UNRESOLVED'
                   END,
                   COALESCE(c.id, e.case_id),
                   :classifiedAt,
                   :classifiedBy
              FROM {h-schema}ethics_worm_audit w
              LEFT JOIN {h-schema}ethics_cases c ON c.id = w.aggregate_id
              LEFT JOIN {h-schema}ethics_evidence_attachments e ON e.id = w.aggregate_id
             WHERE w.id = :wormAuditId
            ON CONFLICT (worm_audit_id) DO NOTHING
            """, nativeQuery = true)
    int classify(
            @Param("wormAuditId") UUID wormAuditId,
            @Param("classifiedAt") Instant classifiedAt,
            @Param("classifiedBy") String classifiedBy);

    /** Zero is the invariant. Anything else is a ledger row that outlived its own meaning. */
    @Query(value = """
            SELECT count(*)
              FROM {h-schema}ethics_worm_audit w
             WHERE NOT EXISTS (
                   SELECT 1 FROM {h-schema}ethics_audit_scope s WHERE s.worm_audit_id = w.id)
            """, nativeQuery = true)
    long countUnclassifiedLedgerRows();
}
