package com.example.auditconsumer.audit;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Component;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — Postgres transaction-scoped
 * advisory lock implementation of {@link AuditChainLock} (BE-016
 * {@code PgAdvisoryAuditChainLock} reuse).
 *
 * <p>Issues {@code SELECT pg_advisory_xact_lock(?)} with the numeric tenant key
 * (companyId) directly as the 64-bit advisory key. The lock is held for the
 * remainder of the current transaction and released automatically on
 * commit/rollback — no explicit unlock path, so it is safe against leaks even if
 * the audit write throws.
 */
@Component
public class PgAdvisoryAuditChainLock implements AuditChainLock {

    @PersistenceContext
    private EntityManager entityManager;

    @Override
    public void lockTenantChain(long tenantId) {
        entityManager
                .createNativeQuery("SELECT pg_advisory_xact_lock(:key)")
                .setParameter("key", tenantId)
                .getSingleResult();
    }
}
