package com.example.auditconsumer.service;

import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.audit.AuditChainSupport;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — maps a Redis stream record to an
 * immutable, hash-chained {@link AuditEvent} row.
 *
 * <p>Persist sequence (BE-016 {@code EndpointAuditService} pattern reuse):
 * <ol>
 *   <li>map + validate the stream fields (drop malformed → poison-message safe);</li>
 *   <li>idempotency short-circuit on {@code dedup_key} (so a redelivery / replay
 *       does not even take the chain lock);</li>
 *   <li>acquire the per-tenant {@link AuditChainLock};</li>
 *   <li>read the chain tail under the lock → {@code prev_hash};</li>
 *   <li>compute {@code entry_hash} and INSERT atomically with
 *       {@code ON CONFLICT (dedup_key) DO NOTHING}.</li>
 * </ol>
 *
 * <p>Idempotency is enforced two ways: an existence probe (fast path) and an
 * atomic {@code INSERT ... ON CONFLICT (dedup_key) DO NOTHING} (authoritative).
 * The atomic upsert is what makes a redelivery <em>race</em> safe on real
 * PostgreSQL: it returns 1 (inserted) or 0 (dedup_key already present →
 * ack-able DUPLICATE) <em>without</em> raising a unique-violation, so the
 * transaction is never left in PG's aborted state. A {@code save()}→catch→
 * {@code existsByDedupKey} classifier would instead trip the unique constraint,
 * abort the transaction, and then fail the follow-up SELECT with "current
 * transaction is aborted" — turning a benign duplicate into an unacked
 * retry/PEL loop. Any OTHER integrity violation (the {@code UNIQUE(id)}, a
 * {@code NOT NULL}, or the require-hash trigger) is NOT a {@code dedup_key}
 * conflict, so the native insert still raises a
 * {@link DataIntegrityViolationException} that propagates out — the consumer
 * does NOT ACK it and the event stays in the PEL for retry/DLQ rather than
 * being silently lost.
 *
 * <p>Tenant identity is the numeric backend companyId (audio-gateway JWT-claim
 * contract: {@code tenantId}/{@code userId} XADDed as numeric strings), NOT a
 * UUID org_id — this consumer aligns to the live producer contract.
 */
@Service
public class AuditEventPersistenceService {

    private static final Logger log = LoggerFactory.getLogger(AuditEventPersistenceService.class);

    /** Outcome of a single persist attempt — drives the consumer ack/skip decision. */
    public enum PersistResult {
        /** New row inserted. */
        PERSISTED,
        /** Already present (idempotent redelivery) — ack and skip. */
        DUPLICATE,
        /** Malformed / unmappable record — route to DLQ then ack (poison-message safe). */
        INVALID
    }

    /**
     * Persist outcome + (for {@link PersistResult#INVALID}) the parse-failure
     * reason so the consumer can record an actionable DLQ entry. {@code reason}
     * is null for non-INVALID outcomes.
     */
    public record PersistOutcome(PersistResult result, String reason) {
        static PersistOutcome of(PersistResult result) {
            return new PersistOutcome(result, null);
        }

        static PersistOutcome invalid(String reason) {
            return new PersistOutcome(PersistResult.INVALID, reason);
        }
    }

    private final AuditEventRepository repository;
    private final AuditChainLock chainLock;
    private final Clock clock;

    public AuditEventPersistenceService(AuditEventRepository repository,
                                        AuditChainLock chainLock,
                                        Clock clock) {
        this.repository = repository;
        this.chainLock = chainLock;
        this.clock = clock;
    }

    /**
     * Persist one audit event from its Redis stream fields. {@code streamEntryId}
     * is the Redis-assigned entry id (forensics only — NOT the idempotency key;
     * the producer-supplied natural key is).
     *
     * <p>{@code REQUIRES_NEW} so each event commits in its own transaction —
     * one poison/duplicate event never rolls back a batch of good ones, and the
     * advisory lock is scoped to exactly this event's write.
     *
     * <p>The write is an atomic {@code INSERT ... ON CONFLICT (dedup_key) DO
     * NOTHING}: a {@code dedup_key} redelivery race resolves to a DUPLICATE
     * (0 affected rows) without raising — so the transaction is never aborted.
     * A non-dedup {@link DataIntegrityViolationException} (a different
     * constraint) still propagates out of this method (it is NOT swallowed as a
     * duplicate) so the consumer leaves the entry unacked for redelivery — only
     * a dedup_key collision is an ack-able duplicate.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistOutcome persist(Map<String, String> fields, String streamEntryId) {
        final AuditEvent event;
        try {
            event = map(fields);
        } catch (RuntimeException ex) {
            log.warn("Dropping malformed audit record entryId={} err={} msg={}",
                    streamEntryId, ex.getClass().getSimpleName(), ex.getMessage());
            return PersistOutcome.invalid(ex.getClass().getSimpleName() + ": " + ex.getMessage());
        }
        event.setStreamEntryId(streamEntryId);

        // Fast-path idempotency: a redelivery should not take the chain lock.
        if (repository.existsByDedupKey(event.getDedupKey())) {
            log.debug("Skipping duplicate audit event dedupKey={} entryId={}",
                    event.getDedupKey(), streamEntryId);
            return PersistOutcome.of(PersistResult.DUPLICATE);
        }

        // Lock the tenant chain BEFORE reading the tail so the prev_hash link is
        // race-free even under a future parallel-persist refactor.
        chainLock.lockTenantChain(event.getTenantId());
        String prevHash = repository.findTop1ByTenantIdOrderBySeqDesc(event.getTenantId())
                .map(AuditEvent::getEntryHash)
                .orElse(null);

        event.setEntryHashAlg(AuditChainSupport.HASH_ALGORITHM);
        event.setEntryHashVersion(AuditChainSupport.HASH_VERSION);
        event.setPrevHash(prevHash);
        event.setEntryHash(AuditChainSupport.computeEntryHash(prevHash, event));

        // Atomic idempotent insert: ON CONFLICT (dedup_key) DO NOTHING returns
        // the affected-row count. 1 = inserted; 0 = a racing duplicate slipped
        // past the existence probe and the dedup_key already exists — an ack-able
        // DUPLICATE that, unlike a save()→unique-violation→catch path, does NOT
        // abort the transaction (so no "current transaction is aborted" on any
        // follow-up statement, and the consumer can ACK it cleanly).
        //
        // A NON-dedup integrity violation (UNIQUE(id), NOT NULL, the require-hash
        // BEFORE-INSERT trigger) is NOT the ON CONFLICT target, so it raises a
        // DataIntegrityViolationException here that propagates out — REQUIRES_NEW
        // rolls back and the consumer leaves the entry unacked for redelivery/DLQ
        // (no silent audit loss).
        int affected = repository.insertOnConflictDoNothing(
                event.getId(), event.getTenantId(), event.getEventType(), event.getSessionId(),
                event.getUserId(), event.getChunkSeq(), event.getHttpStatus(), event.getRejectionCode(),
                event.getRetryAfterSeconds(), event.getCorrelationId(), event.getEventTimestamp(),
                event.getDedupKey(), event.getStreamEntryId(), event.getPrevHash(),
                event.getEntryHash(), event.getEntryHashAlg(), event.getEntryHashVersion());
        if (affected == 0) {
            log.debug("Duplicate on insert (ON CONFLICT dedup_key) dedupKey={} entryId={}",
                    event.getDedupKey(), streamEntryId);
            return PersistOutcome.of(PersistResult.DUPLICATE);
        }
        return PersistOutcome.of(PersistResult.PERSISTED);
    }

    /**
     * Map raw stream fields to an {@link AuditEvent}. Throws if a required field
     * (eventType, tenantId, timestamp) is missing/invalid so the caller can drop
     * the poison record. Empty-string field values are treated as null (the
     * producer maps absent nullable Longs to empty strings).
     */
    private AuditEvent map(Map<String, String> fields) {
        final String eventType = required(fields, "eventType");
        // Producer contract: tenantId is the numeric backend companyId, XADDed as
        // a numeric string (NOT a UUID). UUID-parsing it would fail every live
        // event → event loss; parse as Long to match the producer.
        final long tenantId = parseLongStrict(req(fields, "tenantId"), "tenantId");
        final long timestampMs = parseLongStrict(req(fields, "timestampMs"), "timestampMs");

        final AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        event.setEventType(eventType);
        event.setSessionId(emptyToNull(fields.get("sessionId")));
        event.setUserId(parseLongOrNull(fields.get("userId")));
        event.setChunkSeq(parseLongOrNull(fields.get("chunkSeq")));
        event.setHttpStatus(parseIntOrNull(fields.get("httpStatus")));
        event.setRejectionCode(emptyToNull(fields.get("rejectionCode")));
        event.setRetryAfterSeconds(parseLongOrNull(fields.get("retryAfterSeconds")));
        event.setCorrelationId(emptyToNull(fields.get("correlationId")));
        event.setEventTimestamp(
                AuditChainSupport.normalizeTimestamp(java.time.Instant.ofEpochMilli(timestampMs)));
        event.setDedupKey(dedupKey(eventType, fields));
        return event;
    }

    /**
     * Natural-key idempotency token. For {@code CHUNK_ADMISSION_REJECTED} the
     * producer guarantees {@code sessionId:chunkSeq} session-chunk identity;
     * combined with eventType it is unique per logical event. Falls back to a
     * tenant+timestamp+correlation composite for any future event type that
     * lacks a session/chunk identity (still stable for redelivery of the SAME
     * stream entry, because the same fields produce the same key).
     */
    private String dedupKey(String eventType, Map<String, String> fields) {
        String sessionId = emptyToNull(fields.get("sessionId"));
        String chunkSeq = emptyToNull(fields.get("chunkSeq"));
        if (sessionId != null && chunkSeq != null) {
            return eventType + ":" + sessionId + ":" + chunkSeq;
        }
        // Fallback composite — bounded length (column is VARCHAR(320)).
        String composite = eventType + ":"
                + emptyToNull(fields.get("tenantId")) + ":"
                + emptyToNull(fields.get("timestampMs")) + ":"
                + emptyToNull(fields.get("correlationId"));
        return composite.length() > 320 ? composite.substring(0, 320) : composite;
    }

    private static String required(Map<String, String> fields, String key) {
        String v = emptyToNull(fields.get(key));
        if (v == null) {
            throw new IllegalArgumentException("missing required field '" + key + "'");
        }
        return v;
    }

    private static String req(Map<String, String> fields, String key) {
        return required(fields, key);
    }

    private static long parseLongStrict(String raw, String field) {
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " is not a long: '" + raw + "'");
        }
    }

    private static Long parseLongOrNull(String raw) {
        String v = emptyToNull(raw);
        if (v == null) {
            return null;
        }
        try {
            return Long.parseLong(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Integer parseIntOrNull(String raw) {
        String v = emptyToNull(raw);
        if (v == null) {
            return null;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
