package com.example.auditconsumer.service;

import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.audit.AuditChainSupport;
import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.model.RecordingConsentGrant;
import com.example.auditconsumer.model.RecordingConsentRevocation;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.repository.ConsentEventOutboxRepository;
import com.example.auditconsumer.repository.RecordingConsentGrantRepository;
import com.example.auditconsumer.repository.RecordingConsentRevocationRepository;
import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;
import com.example.common.meeting.events.MeetingEventV1Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final String CONSENT_GRANTED = "RECORDING_CONSENT_GRANTED";
    private static final String CONSENT_REVOKED = "RECORDING_CONSENT_REVOKED";
    private static final String CONSENT_AGGREGATE_TYPE = "meeting.consent";
    private static final String PRODUCER = "audit-event-consumer-service";
    private static final Instant EARLIEST_ACCEPTED_EVENT = Instant.parse("2000-01-01T00:00:00Z");
    private static final Duration MAX_FUTURE_CLOCK_SKEW = Duration.ofMinutes(5);

    /** Outcome of a single persist attempt — drives the consumer ack/skip decision. */
    public enum PersistResult {
        /** New row inserted. */
        PERSISTED,
        /** Already present (idempotent redelivery) — ack and skip. */
        DUPLICATE,
        /** Same natural key with different material content — route to conflict DLQ then ack. */
        CONFLICT,
        /** A required durable predecessor has not arrived yet — leave pending for bounded retry. */
        RETRYABLE,
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

        static PersistOutcome conflict(String reason) {
            return new PersistOutcome(PersistResult.CONFLICT, reason);
        }

        static PersistOutcome retryable(String reason) {
            return new PersistOutcome(PersistResult.RETRYABLE, reason);
        }
    }

    private final AuditEventRepository repository;
    private final AuditChainLock chainLock;
    private final Clock clock;
    private final RecordingConsentGrantRepository grantRepository;
    private final RecordingConsentRevocationRepository revocationRepository;
    private final ConsentEventOutboxRepository outboxRepository;

    public AuditEventPersistenceService(AuditEventRepository repository,
                                        AuditChainLock chainLock,
                                        Clock clock) {
        this(repository, chainLock, clock, null, null, null);
    }

    @Autowired
    public AuditEventPersistenceService(
            AuditEventRepository repository,
            AuditChainLock chainLock,
            Clock clock,
            RecordingConsentGrantRepository grantRepository,
            RecordingConsentRevocationRepository revocationRepository,
            ConsentEventOutboxRepository outboxRepository) {
        this.repository = repository;
        this.chainLock = chainLock;
        this.clock = clock;
        this.grantRepository = grantRepository;
        this.revocationRepository = revocationRepository;
        this.outboxRepository = outboxRepository;
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
        final GrantProjection grant;
        final RevocationProjection revocation;
        try {
            event = map(fields);
            grant = CONSENT_GRANTED.equals(event.getEventType())
                    ? mapGrant(fields, event)
                    : null;
            revocation = CONSENT_REVOKED.equals(event.getEventType())
                    ? mapRevocation(fields, event)
                    : null;
        } catch (org.springframework.dao.DataAccessException ex) {
            throw ex;
        } catch (ConsentDependencyPendingException ex) {
            log.info("Consent predecessor not available yet entryId={}", streamEntryId);
            return PersistOutcome.retryable("CONSENT_DEPENDENCY_PENDING");
        } catch (RuntimeException ex) {
            log.warn("Rejecting malformed audit record entryId={} err={}",
                    streamEntryId, ex.getClass().getSimpleName());
            return PersistOutcome.invalid("INVALID_EVENT");
        }
        event.setStreamEntryId(streamEntryId);

        // Fast-path idempotency: a redelivery should not take the chain lock.
        if (repository.existsByDedupKey(event.getDedupKey())) {
            if ((grant != null && !duplicateGrantMatches(grant))
                    || (revocation != null && !duplicateRevocationMatches(revocation))) {
                return PersistOutcome.conflict("IDEMPOTENCY_CONFLICT");
            }
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
            if ((grant != null && !duplicateGrantMatches(grant))
                    || (revocation != null && !duplicateRevocationMatches(revocation))) {
                return PersistOutcome.conflict("IDEMPOTENCY_CONFLICT");
            }
            log.debug("Duplicate on insert (ON CONFLICT dedup_key) dedupKey={} entryId={}",
                    event.getDedupKey(), streamEntryId);
            return PersistOutcome.of(PersistResult.DUPLICATE);
        }
        if (grant != null) {
            persistGrant(grant);
        }
        if (revocation != null) {
            persistRevocationAndOutbox(revocation);
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
        final Instant eventTimestamp = parseEventTimestamp(eventType, fields);

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
        event.setEventTimestamp(AuditChainSupport.normalizeTimestamp(eventTimestamp));
        event.setDedupKey(dedupKey(eventType, fields));
        if (CONSENT_GRANTED.equals(eventType)) {
            event.setSessionId(parseUuidStrict(required(fields, "captureId"), "captureId").toString());
            event.setChunkSeq(1L);
        } else if (CONSENT_REVOKED.equals(eventType)) {
            event.setSessionId(parseUuidStrict(required(fields, "captureId"), "captureId").toString());
            event.setChunkSeq(parseLongStrict(required(fields, "consentRevision"), "consentRevision"));
            event.setRejectionCode(required(fields, "reasonCode"));
        }
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
        if (CONSENT_GRANTED.equals(eventType)) {
            return eventType + ":"
                    + parseUuidStrict(required(fields, "captureId"), "captureId");
        }
        if (CONSENT_REVOKED.equals(eventType)) {
            return eventType + ":"
                    + parseUuidStrict(required(fields, "captureId"), "captureId") + ":"
                    + parseLongStrict(required(fields, "consentRevision"), "consentRevision");
        }
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

    private GrantProjection mapGrant(Map<String, String> fields, AuditEvent audit) {
        requireConsentRepositories();
        UUID meetingId = parseUuidStrict(required(fields, "meetingId"), "meetingId");
        UUID captureId = parseUuidStrict(required(fields, "captureId"), "captureId");
        UUID tenantId = optionalUuid(fields.get("canonicalTenantId"), "canonicalTenantId");
        UUID orgId = optionalUuid(fields.get("orgId"), "orgId");
        if ((tenantId == null) != (orgId == null)) {
            throw new IllegalArgumentException("canonical consent scope must be complete");
        }
        String actorSubject = bounded(required(fields, "subjectId"), "subjectId", 255);
        long actorUserId = parseLongStrict(required(fields, "userId"), "userId");
        String consentVersion = bounded(required(fields, "consentVersion"), "consentVersion", 64);
        if (!consentVersion.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("consentVersion has invalid format");
        }
        String consentTextHash = required(fields, "consentTextHash");
        if (!consentTextHash.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("consentTextHash has invalid format");
        }
        String locale = required(fields, "locale");
        if (!locale.matches("[a-z]{2}(-[A-Z]{2})?")) {
            throw new IllegalArgumentException("locale has invalid format");
        }
        long revision = 1L;
        String eventKey = CONSENT_GRANTED + "|" + captureId;
        String sourceHash = sha256Hex(String.join("\n",
                meetingId.toString(), captureId.toString(), Long.toString(audit.getTenantId()),
                Long.toString(actorUserId), uuidOrEmpty(tenantId), uuidOrEmpty(orgId), actorSubject,
                consentVersion, consentTextHash, locale, Long.toString(revision)));
        return new GrantProjection(
                meetingId, captureId, audit.getTenantId(), tenantId, orgId,
                actorUserId, actorSubject, consentVersion, consentTextHash, locale, revision,
                emptyToNull(fields.get("correlationId")), audit.getEventTimestamp(), eventKey, sourceHash);
    }

    private void persistGrant(GrantProjection value) {
        RecordingConsentGrant row = new RecordingConsentGrant();
        row.setId(UUID.randomUUID());
        row.setEventKey(value.eventKey());
        row.setSourceHash(value.sourceHash());
        row.setMeetingId(value.meetingId());
        row.setCaptureId(value.captureId());
        row.setSourceTenantId(value.sourceTenantId());
        row.setTenantId(value.tenantId());
        row.setOrgId(value.orgId());
        row.setActorSubject(value.actorSubject());
        row.setActorUserId(value.actorUserId());
        row.setConsentVersion(value.consentVersion());
        row.setConsentTextHash(value.consentTextHash());
        row.setLocale(value.locale());
        row.setConsentRevision(value.consentRevision());
        row.setCorrelationId(value.correlationId());
        row.setGrantedAt(value.grantedAt());
        grantRepository.saveAndFlush(row);
    }

    private RevocationProjection mapRevocation(Map<String, String> fields, AuditEvent audit) {
        requireConsentRepositories();
        UUID meetingId = parseUuidStrict(required(fields, "meetingId"), "meetingId");
        UUID captureId = parseUuidStrict(required(fields, "captureId"), "captureId");
        UUID tenantId = parseUuidStrict(required(fields, "canonicalTenantId"), "canonicalTenantId");
        UUID orgId = parseUuidStrict(required(fields, "orgId"), "orgId");
        String actorSubject = bounded(required(fields, "subjectId"), "subjectId", 255);
        long actorUserId = parseLongStrict(required(fields, "userId"), "userId");
        String consentVersion = bounded(required(fields, "consentVersion"), "consentVersion", 64);
        if (!consentVersion.matches("[A-Za-z0-9._:-]+")) {
            throw new IllegalArgumentException("consentVersion has invalid format");
        }
        long requestedRevision = parseLongStrict(required(fields, "consentRevision"), "consentRevision");
        String reasonCode = bounded(required(fields, "reasonCode"), "reasonCode", 64);
        if (!"USER_WITHDREW".equals(reasonCode)) {
            throw new IllegalArgumentException("reasonCode has invalid format");
        }
        RecordingConsentGrant grant = grantRepository.findByCaptureId(captureId)
                .orElseThrow(ConsentDependencyPendingException::new);
        long revision = Math.addExact(grant.getConsentRevision(), 1L);
        if (requestedRevision != revision
                || !meetingId.equals(grant.getMeetingId())
                || audit.getTenantId() != grant.getSourceTenantId()
                || actorUserId != grant.getActorUserId()
                || (grant.getTenantId() != null && !tenantId.equals(grant.getTenantId()))
                || (grant.getOrgId() != null && !orgId.equals(grant.getOrgId()))
                || !actorSubject.equals(grant.getActorSubject())
                || !consentVersion.equals(grant.getConsentVersion())
                || audit.getEventTimestamp().isBefore(grant.getGrantedAt())) {
            throw new IllegalArgumentException("consent grant ownership or scope mismatch");
        }
        String eventKey = CONSENT_AGGREGATE_TYPE + "|" + captureId + "|"
                + MeetingEventType.CONSENT_REVOKED.wireValue() + "|" + revision;
        String sourceHash = sha256Hex(String.join("\n",
                meetingId.toString(),
                captureId.toString(),
                Long.toString(audit.getTenantId()),
                Long.toString(actorUserId),
                tenantId.toString(),
                orgId.toString(),
                actorSubject,
                consentVersion,
                Long.toString(revision),
                reasonCode));
        return new RevocationProjection(
                meetingId, captureId, audit.getTenantId(), tenantId, orgId, actorUserId,
                actorSubject, consentVersion, revision, reasonCode,
                emptyToNull(fields.get("correlationId")), audit.getEventTimestamp(), eventKey, sourceHash);
    }

    private void persistRevocationAndOutbox(RevocationProjection value) {
        RecordingConsentRevocation row = new RecordingConsentRevocation();
        row.setId(UUID.randomUUID());
        row.setEventKey(value.eventKey());
        row.setSourceHash(value.sourceHash());
        row.setMeetingId(value.meetingId());
        row.setCaptureId(value.captureId());
        row.setSourceTenantId(value.sourceTenantId());
        row.setTenantId(value.tenantId());
        row.setOrgId(value.orgId());
        row.setActorSubject(value.actorSubject());
        row.setActorUserId(value.actorUserId());
        row.setConsentVersion(value.consentVersion());
        row.setConsentRevision(value.consentRevision());
        row.setReasonCode(value.reasonCode());
        row.setCorrelationId(value.correlationId());
        row.setRevokedAt(value.revokedAt());

        MeetingEventEnvelope envelope = MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.CONSENT_REVOKED)
                .producer(PRODUCER)
                .meetingId(value.meetingId())
                .tenantId(value.tenantId())
                .orgId(value.orgId())
                .occurredAt(value.revokedAt())
                .aggregateType(CONSENT_AGGREGATE_TYPE)
                .aggregateId(value.captureId())
                .aggregateRevision(value.consentRevision())
                .payload(new MeetingEventPayload.ConsentRevoked(
                        value.captureId(), value.consentVersion(), value.consentRevision(), value.reasonCode()))
                .build();
        String payload = MeetingEventV1Serializer.toJson(envelope);

        ConsentEventOutbox outbox = new ConsentEventOutbox();
        outbox.setId(UUID.randomUUID());
        outbox.setEventType(MeetingEventType.CONSENT_REVOKED.wireValue());
        outbox.setEventKey(envelope.eventKey());
        outbox.setAggregateId(value.captureId());
        outbox.setMeetingId(value.meetingId());
        outbox.setTenantId(value.tenantId());
        outbox.setOrgId(value.orgId());
        outbox.setPayload(payload);
        outbox.setPayloadHash(sha256Hex(payload));

        revocationRepository.saveAndFlush(row);
        outboxRepository.saveAndFlush(outbox);
    }

    private boolean duplicateGrantMatches(GrantProjection incoming) {
        return grantRepository.findByEventKey(incoming.eventKey())
                .map(existing -> hashesEqual(existing.getSourceHash(), incoming.sourceHash()))
                .orElse(false);
    }

    private boolean duplicateRevocationMatches(RevocationProjection incoming) {
        return revocationRepository.findByEventKey(incoming.eventKey())
                .filter(existing -> hashesEqual(existing.getSourceHash(), incoming.sourceHash()))
                .filter(existing -> outboxRepository.findByEventKey(incoming.eventKey()).isPresent())
                .isPresent();
    }

    private static boolean hashesEqual(String left, String right) {
        return left != null && right != null && MessageDigest.isEqual(
                left.getBytes(StandardCharsets.US_ASCII),
                right.getBytes(StandardCharsets.US_ASCII));
    }

    private static String timestampValue(String eventType, Map<String, String> fields) {
        String timestamp = emptyToNull(fields.get("timestampMs"));
        if (timestamp != null) {
            return timestamp;
        }
        if (CONSENT_GRANTED.equals(eventType)) {
            return required(fields, "acceptedAtMs");
        }
        if (CONSENT_REVOKED.equals(eventType)) {
            return required(fields, "revokedAtMs");
        }
        return required(fields, "timestampMs");
    }

    private Instant parseEventTimestamp(String eventType, Map<String, String> fields) {
        long timestampMs = parseLongStrict(timestampValue(eventType, fields), "timestampMs");
        final Instant timestamp;
        try {
            timestamp = Instant.ofEpochMilli(timestampMs);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("timestampMs is outside the supported range", ex);
        }
        Instant latestAccepted = clock.instant().plus(MAX_FUTURE_CLOCK_SKEW);
        if (timestamp.isBefore(EARLIEST_ACCEPTED_EVENT) || timestamp.isAfter(latestAccepted)) {
            throw new IllegalArgumentException("timestampMs is outside the accepted audit window");
        }
        return timestamp;
    }

    private static UUID optionalUuid(String raw, String fieldName) {
        String value = emptyToNull(raw);
        return value == null ? null : parseUuidStrict(value, fieldName);
    }

    private static String uuidOrEmpty(UUID value) {
        return value == null ? "" : value.toString();
    }

    private static final class ConsentDependencyPendingException extends RuntimeException {
        private ConsentDependencyPendingException() {
            super("consent predecessor pending");
        }
    }

    private void requireConsentRepositories() {
        if (grantRepository == null || revocationRepository == null || outboxRepository == null) {
            throw new IllegalStateException("Consent repositories are unavailable");
        }
    }

    private static UUID parseUuidStrict(String raw, String field) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " is not a UUID: '" + raw + "'");
        }
    }

    private static String bounded(String value, String field, int maxLength) {
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(field + " exceeds max length " + maxLength);
        }
        return value;
    }

    private static String sha256Hex(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private record GrantProjection(
            UUID meetingId,
            UUID captureId,
            long sourceTenantId,
            UUID tenantId,
            UUID orgId,
            long actorUserId,
            String actorSubject,
            String consentVersion,
            String consentTextHash,
            String locale,
            long consentRevision,
            String correlationId,
            java.time.Instant grantedAt,
            String eventKey,
            String sourceHash) {
    }

    private record RevocationProjection(
            UUID meetingId,
            UUID captureId,
            long sourceTenantId,
            UUID tenantId,
            UUID orgId,
            long actorUserId,
            String actorSubject,
            String consentVersion,
            long consentRevision,
            String reasonCode,
            String correlationId,
            java.time.Instant revokedAt,
            String eventKey,
            String sourceHash) {
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
