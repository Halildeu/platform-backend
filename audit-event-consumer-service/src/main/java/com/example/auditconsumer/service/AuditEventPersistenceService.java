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
 *   <li>compute {@code entry_hash} and INSERT.</li>
 * </ol>
 *
 * <p>Idempotency is enforced two ways: an existence probe (fast path) and the
 * DB {@code dedup_key} UNIQUE constraint (authoritative). A concurrent / racing
 * duplicate that slips past the probe surfaces as a
 * {@link DataIntegrityViolationException} which is treated as "already persisted"
 * → {@link PersistResult#DUPLICATE} (ack-and-skip).
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
        /** Malformed / unmappable record — ack and skip (poison-message safe). */
        INVALID
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
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PersistResult persist(Map<String, String> fields, String streamEntryId) {
        final AuditEvent event;
        try {
            event = map(fields);
        } catch (RuntimeException ex) {
            log.warn("Dropping malformed audit record entryId={} err={} msg={}",
                    streamEntryId, ex.getClass().getSimpleName(), ex.getMessage());
            return PersistResult.INVALID;
        }
        event.setStreamEntryId(streamEntryId);

        // Fast-path idempotency: a redelivery should not take the chain lock.
        if (repository.existsByDedupKey(event.getDedupKey())) {
            log.debug("Skipping duplicate audit event dedupKey={} entryId={}",
                    event.getDedupKey(), streamEntryId);
            return PersistResult.DUPLICATE;
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

        try {
            repository.save(event);
            return PersistResult.PERSISTED;
        } catch (DataIntegrityViolationException ex) {
            // Lost a race on the dedup_key unique constraint — already persisted.
            log.debug("Duplicate on insert (unique dedup_key) dedupKey={} entryId={}",
                    event.getDedupKey(), streamEntryId);
            return PersistResult.DUPLICATE;
        }
    }

    /**
     * Map raw stream fields to an {@link AuditEvent}. Throws if a required field
     * (eventType, tenantId, timestamp) is missing/invalid so the caller can drop
     * the poison record. Empty-string field values are treated as null (the
     * producer maps absent nullable Longs to empty strings).
     */
    private AuditEvent map(Map<String, String> fields) {
        final String eventType = required(fields, "eventType");
        final UUID tenantId = parseTenantId(req(fields, "tenantId"));
        final long timestampMs = parseLongStrict(req(fields, "timestampMs"), "timestampMs");

        final AuditEvent event = new AuditEvent();
        event.setId(UUID.randomUUID());
        event.setTenantId(tenantId);
        // Set org_id = tenant_id in the APPLICATION (not only via the DB compat
        // trigger) so the value hashed before INSERT matches the value re-read on
        // verification. If org_id were left null here, the canonical payload would
        // hash org_id=null while the trigger backfills it to tenant_id → the
        // verifier's re-hash would mismatch the stored entry_hash. The DB trigger
        // stays as a redundant safety net for any other writer.
        event.setOrgId(tenantId);
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

    private static UUID parseTenantId(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("tenantId is not a UUID: '" + raw + "'");
        }
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
