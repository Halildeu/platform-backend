package com.example.auditconsumer.repository;

import com.example.auditconsumer.model.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — audit_event repository.
 *
 * <p><b>Insert + select only.</b> No update/delete query method is declared, and
 * the entity is {@code @Immutable}; the DB-level append-only trigger is the hard
 * backstop. {@code save()} only ever produces an INSERT (the id is null/seq is
 * DB-assigned and {@code @Immutable} suppresses dirty UPDATE).
 */
public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    /** Tenant chain tail: the most recent row by seq (chain-tail lookup). */
    Optional<AuditEvent> findTop1ByTenantIdOrderBySeqDesc(UUID tenantId);

    /** Verifier feed: every row for a tenant in chain order (oldest → newest). */
    List<AuditEvent> findByTenantIdOrderBySeqAsc(UUID tenantId);

    /** Idempotency probe (defence-in-depth; the unique constraint is authoritative). */
    boolean existsByDedupKey(String dedupKey);
}
