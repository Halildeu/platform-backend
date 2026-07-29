package com.example.ethics.audit;

import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.WormAuditEntry;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.AuditScopeRepository;
import com.example.ethics.repository.WormAuditRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * One outbox row → one append-only ledger row + its classification + checkpoint transaction.
 *
 * <p>A crash before commit produces neither result. A crash after commit
 * produces both. A replay is accepted only when the existing immutable row
 * matches the exact outbox material.
 *
 * <p>The classification (ES-302, #884) is written here rather than by a later pass because it
 * is derived from the ledger row's parents, and erasure destroys those parents. Written in
 * this transaction it is guaranteed; written later it is guesswork with an expiry date.
 */
@Service
public class AuditDeliveryService {
    private final AuditOutboxRepository outbox;
    private final WormAuditRepository worm;
    private final AuditScopeRepository scope;
    private final EthicsAuditChainLock chainLock;

    public AuditDeliveryService(
            AuditOutboxRepository outbox,
            WormAuditRepository worm,
            AuditScopeRepository scope,
            EthicsAuditChainLock chainLock) {
        this.outbox = outbox;
        this.worm = worm;
        this.scope = scope;
        this.chainLock = chainLock;
    }

    @Transactional
    public DeliveryResult deliver(
            UUID outboxId,
            UUID claimToken,
            Instant lockedUntil,
            Instant deliveredAt) {
        AuditOutbox row = outbox.findById(outboxId)
                .orElseThrow(() -> new IllegalStateException("Claimed audit outbox row disappeared"));
        requireClaim(row, claimToken, lockedUntil);

        chainLock.lock(row.getOrgId());
        boolean inserted = appendOrVerifyDuplicate(row, deliveredAt);
        int checkpointed = outbox.markDelivered(outboxId, claimToken, lockedUntil, deliveredAt);
        if (checkpointed != 1) {
            throw new IllegalStateException("Audit outbox checkpoint CAS fence rejected stale worker");
        }
        return inserted ? DeliveryResult.INSERTED : DeliveryResult.IDEMPOTENT_REPLAY;
    }

    private boolean appendOrVerifyDuplicate(AuditOutbox row, Instant ingestedAt) {
        WormAuditEntry existing = worm.findBySourceOutboxId(row.getId()).orElse(null);
        if (existing != null) {
            verifySameMaterial(existing, row);
            return false;
        }

        String previousHash = worm.findTop1ByOrgIdOrderBySeqDesc(row.getOrgId())
                .map(WormAuditEntry::getEntryHash)
                .orElse(null);
        String entryHash = EthicsAuditChain.computeEntryHash(previousHash, row);
        UUID ledgerId = UUID.randomUUID();
        int inserted = worm.insertOnConflictDoNothing(
                ledgerId,
                row.getId(),
                row.getOrgId(),
                row.getAggregateId(),
                row.getEventType(),
                row.getPayload(),
                EthicsAuditChain.normalizeTimestamp(row.getCreatedAt()),
                EthicsAuditChain.normalizeTimestamp(ingestedAt),
                previousHash,
                entryHash,
                EthicsAuditChain.HASH_ALGORITHM,
                EthicsAuditChain.HASH_VERSION);
        if (inserted == 1) {
            // Same transaction as the append: the ledger row and the record of what it belongs
            // to either both exist or neither does. A row that reached the ledger without this
            // could never be classified afterwards, because erasure removes what the answer is
            // derived from.
            scope.classify(ledgerId, ingestedAt, AuditScopeRepository.WRITER);
            return true;
        }

        // A racing duplicate is safe only if its immutable material is exact.
        WormAuditEntry raced = worm.findBySourceOutboxId(row.getId())
                .orElseThrow(() -> new IllegalStateException(
                        "WORM idempotency conflict produced no readable ledger row"));
        verifySameMaterial(raced, row);
        return false;
    }

    private static void requireClaim(AuditOutbox row, UUID claimToken, Instant lockedUntil) {
        if (!"PROCESSING".equals(row.getStatus())
                || !claimToken.equals(row.getClaimToken())
                || !lockedUntil.equals(row.getLockedUntil())) {
            throw new IllegalStateException("Audit outbox claim CAS fence rejected stale worker");
        }
    }

    private static void verifySameMaterial(WormAuditEntry existing, AuditOutbox row) {
        String expectedHash = EthicsAuditChain.computeEntryHash(existing.getPrevHash(), row);
        boolean same = existing.getSourceOutboxId().equals(row.getId())
                && existing.getOrgId().equals(row.getOrgId())
                && existing.getAggregateId().equals(row.getAggregateId())
                && existing.getEventType().equals(row.getEventType())
                && existing.getPayload().equals(row.getPayload())
                && existing.getEventTimestamp().equals(
                        EthicsAuditChain.normalizeTimestamp(row.getCreatedAt()))
                && existing.getEntryHash().equals(expectedHash)
                && EthicsAuditChain.HASH_ALGORITHM.equals(existing.getEntryHashAlg())
                && existing.getEntryHashVersion() == EthicsAuditChain.HASH_VERSION;
        if (!same) {
            throw new IllegalStateException("WORM source id conflict has different immutable material");
        }
    }

    public enum DeliveryResult {
        INSERTED,
        IDEMPOTENT_REPLAY
    }
}
