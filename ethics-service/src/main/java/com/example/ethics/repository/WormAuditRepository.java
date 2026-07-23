package com.example.ethics.repository;

import com.example.ethics.model.WormAuditEntry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Deliberately insert/read-only. PostgreSQL's append-only trigger remains the
 * authoritative protection even if a future code path bypasses this interface.
 */
public interface WormAuditRepository extends Repository<WormAuditEntry, Long> {

    Optional<WormAuditEntry> findTop1ByOrgIdOrderBySeqDesc(UUID orgId);

    Optional<WormAuditEntry> findBySourceOutboxId(UUID sourceOutboxId);

    List<WormAuditEntry> findByOrgIdOrderBySeqAsc(UUID orgId);

    long count();

    @Modifying
    @Query(value = """
            INSERT INTO {h-schema}ethics_worm_audit (
                id, source_outbox_id, org_id, aggregate_id, event_type, payload,
                event_timestamp, ingested_at, prev_hash, entry_hash,
                entry_hash_alg, entry_hash_version
            ) VALUES (
                :id, :sourceOutboxId, :orgId, :aggregateId, :eventType, :payload,
                :eventTimestamp, :ingestedAt, :prevHash, :entryHash,
                :entryHashAlg, :entryHashVersion
            )
            ON CONFLICT (source_outbox_id) DO NOTHING
            """, nativeQuery = true)
    int insertOnConflictDoNothing(
            @Param("id") UUID id,
            @Param("sourceOutboxId") UUID sourceOutboxId,
            @Param("orgId") UUID orgId,
            @Param("aggregateId") UUID aggregateId,
            @Param("eventType") String eventType,
            @Param("payload") String payload,
            @Param("eventTimestamp") Instant eventTimestamp,
            @Param("ingestedAt") Instant ingestedAt,
            @Param("prevHash") String prevHash,
            @Param("entryHash") String entryHash,
            @Param("entryHashAlg") String entryHashAlg,
            @Param("entryHashVersion") int entryHashVersion);
}
