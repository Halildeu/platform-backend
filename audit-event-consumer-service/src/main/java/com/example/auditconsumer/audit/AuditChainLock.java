package com.example.auditconsumer.audit;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — tenant-scoped serialization lock
 * for the audit hash-chain (BE-016 {@code endpoint-admin AuditChainLock} reuse).
 *
 * <p>Before a new hashed audit row is written, the writer holds the per-tenant
 * chain lock so two concurrent persists for the same tenant cannot both read the
 * same chain tail and fork the chain. The production implementation
 * ({@link PgAdvisoryAuditChainLock}) uses a Postgres transaction-scoped advisory
 * lock; tests on the in-memory engine supply a no-op (real lock behaviour is
 * exercised by the Postgres Testcontainers test).
 *
 * <p>In this service every event is persisted single-threaded by the consumer
 * loop, so chain forking is already avoided by construction. The lock is kept
 * for defence-in-depth and so a future multi-instance/parallel-persist refactor
 * (or a manual/replay write path) stays correct.
 *
 * <p>The tenant key is the numeric companyId (producer contract); it is fed
 * directly to {@code pg_advisory_xact_lock(bigint)} — no hashing/XOR is needed
 * (unlike the endpoint-admin UUID variant).
 */
public interface AuditChainLock {

    /**
     * Acquire the per-tenant audit-chain lock within the current transaction.
     * Must be called inside an active transaction; the lock is held until that
     * transaction ends. {@code tenantId} is the numeric companyId — the 64-bit
     * advisory-lock key directly.
     */
    void lockTenantChain(long tenantId);
}
