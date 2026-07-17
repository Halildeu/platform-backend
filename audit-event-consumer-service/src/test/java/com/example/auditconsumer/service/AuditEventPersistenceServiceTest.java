package com.example.auditconsumer.service;

import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.model.RecordingConsentGrant;
import com.example.auditconsumer.model.RecordingConsentRevocation;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.repository.ConsentEventOutboxRepository;
import com.example.auditconsumer.repository.RecordingConsentGrantRepository;
import com.example.auditconsumer.repository.RecordingConsentRevocationRepository;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistOutcome;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — {@link AuditEventPersistenceService}
 * unit tests (mocked repository + no-op lock; mapping + idempotency + chain
 * composition without a DB). The genuine immutable persist + advisory lock +
 * tamper-detect + the atomic {@code ON CONFLICT (dedup_key) DO NOTHING}
 * duplicate-race semantics are proven on real engines by
 * {@code AuditPipelineEndToEndPostgresIntegrationTest} — a mock cannot reproduce
 * PostgreSQL's aborted-transaction behaviour, so the dedup-race correctness is a
 * Testcontainers gate, not a mock assertion.
 *
 * <p>Tenant identity is the numeric backend companyId (producer contract).
 */
class AuditEventPersistenceServiceTest {

    private static final long TENANT = 42L;

    private AuditEventRepository repository;
    private RecordingConsentGrantRepository grantRepository;
    private RecordingConsentRevocationRepository revocationRepository;
    private ConsentEventOutboxRepository outboxRepository;
    private AuditEventPersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        grantRepository = mock(RecordingConsentGrantRepository.class);
        revocationRepository = mock(RecordingConsentRevocationRepository.class);
        outboxRepository = mock(ConsentEventOutboxRepository.class);
        AuditChainLock noOpLock = tenantId -> { /* no-op */ };
        Clock fixed = Clock.fixed(Instant.parse("2026-06-16T10:00:00Z"), ZoneOffset.UTC);
        service = new AuditEventPersistenceService(
                repository, noOpLock, fixed, grantRepository, revocationRepository, outboxRepository);
    }

    private Map<String, String> rejected(String sessionId, long chunkSeq) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "CHUNK_ADMISSION_REJECTED");
        fields.put("sessionId", sessionId);
        // Producer contract: numeric companyId string (NOT a UUID).
        fields.put("tenantId", Long.toString(TENANT));
        fields.put("userId", "7");
        fields.put("chunkSeq", Long.toString(chunkSeq));
        fields.put("httpStatus", "429");
        fields.put("rejectionCode", "QUEUE_FULL");
        fields.put("retryAfterSeconds", ""); // absent nullable → empty string
        fields.put("correlationId", "corr-1");
        fields.put("timestampMs", Long.toString(Instant.parse("2026-06-16T10:00:00Z").toEpochMilli()));
        return fields;
    }

    private Map<String, String> revoked(long revision, String subject) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "RECORDING_CONSENT_REVOKED");
        fields.put("meetingId", "11111111-1111-1111-1111-111111111111");
        fields.put("captureId", "22222222-2222-2222-2222-222222222222");
        fields.put("tenantId", Long.toString(TENANT));
        fields.put("userId", "7");
        fields.put("canonicalTenantId", "33333333-3333-3333-3333-333333333333");
        fields.put("orgId", "44444444-4444-4444-4444-444444444444");
        fields.put("subjectId", subject);
        fields.put("consentVersion", "v1");
        fields.put("consentRevision", Long.toString(revision));
        fields.put("reasonCode", "USER_WITHDREW");
        fields.put("correlationId", "corr-revoke");
        fields.put("timestampMs", Long.toString(Instant.parse("2026-06-16T10:00:00Z").toEpochMilli()));
        return fields;
    }

    private RecordingConsentGrant durableGrant(String subject) {
        RecordingConsentGrant grant = new RecordingConsentGrant();
        grant.setEventKey("RECORDING_CONSENT_GRANTED|22222222-2222-2222-2222-222222222222");
        grant.setSourceHash("a".repeat(64));
        grant.setMeetingId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
        grant.setCaptureId(UUID.fromString("22222222-2222-2222-2222-222222222222"));
        grant.setSourceTenantId(TENANT);
        grant.setTenantId(UUID.fromString("33333333-3333-3333-3333-333333333333"));
        grant.setOrgId(UUID.fromString("44444444-4444-4444-4444-444444444444"));
        grant.setActorSubject(subject);
        grant.setActorUserId(7L);
        grant.setConsentVersion("v1");
        grant.setConsentTextHash("sha256:" + "b".repeat(64));
        grant.setLocale("tr-TR");
        grant.setConsentRevision(1L);
        grant.setGrantedAt(Instant.parse("2026-06-16T09:59:00Z"));
        return grant;
    }

    private void stubDurableGrant(String subject) {
        when(grantRepository.findByCaptureId(
                UUID.fromString("22222222-2222-2222-2222-222222222222")))
                .thenReturn(Optional.of(durableGrant(subject)));
    }

    /** Stub the atomic insert to report a fresh row (1 affected). */
    private void stubInsertInserts() {
        when(repository.insertOnConflictDoNothing(
                any(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString(),
                any(), anyString(), any(), anyString(), anyString(), any(), anyString(),
                anyString(), anyInt()))
                .thenReturn(1);
    }

    @Test
    void mapsFieldsAndPersistsNewEventAsGenesis() {
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        stubInsertInserts();

        PersistOutcome outcome = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.PERSISTED);

        // Capture the native-insert column arguments (the audit write path is the
        // atomic ON CONFLICT insert, not save()).
        ArgumentCaptor<UUID> id = ArgumentCaptor.forClass(UUID.class);
        ArgumentCaptor<Long> tenantId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> eventType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sessionId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> userId = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Long> chunkSeq = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Integer> httpStatus = ArgumentCaptor.forClass(Integer.class);
        ArgumentCaptor<Long> retryAfter = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<Instant> eventTs = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<String> dedupKey = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> streamEntryId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> prevHash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> entryHash = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> hashAlg = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> hashVersion = ArgumentCaptor.forClass(Integer.class);

        verify(repository).insertOnConflictDoNothing(
                id.capture(), tenantId.capture(), eventType.capture(), sessionId.capture(),
                userId.capture(), chunkSeq.capture(), httpStatus.capture(), any(),
                retryAfter.capture(), any(), eventTs.capture(), dedupKey.capture(),
                streamEntryId.capture(), prevHash.capture(), entryHash.capture(),
                hashAlg.capture(), hashVersion.capture());

        assertThat(id.getValue()).isNotNull();
        assertThat(tenantId.getValue()).isEqualTo(TENANT);
        assertThat(eventType.getValue()).isEqualTo("CHUNK_ADMISSION_REJECTED");
        assertThat(sessionId.getValue()).isEqualTo("sess-1");
        assertThat(chunkSeq.getValue()).isEqualTo(3L);
        assertThat(httpStatus.getValue()).isEqualTo(429);
        assertThat(userId.getValue()).isEqualTo(7L);
        assertThat(retryAfter.getValue()).isNull(); // empty → null
        assertThat(eventTs.getValue()).isNotNull();
        assertThat(dedupKey.getValue()).isEqualTo("CHUNK_ADMISSION_REJECTED:sess-1:3");
        assertThat(streamEntryId.getValue()).isEqualTo("1700000000000-0");
        // Genesis: prev null, entry hash present.
        assertThat(prevHash.getValue()).isNull();
        assertThat(entryHash.getValue()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(hashAlg.getValue()).isEqualTo("SHA-256");
        assertThat(hashVersion.getValue()).isEqualTo(1);
    }

    @Test
    void chainsToPriorTailHash() {
        com.example.auditconsumer.model.AuditEvent tail = new com.example.auditconsumer.model.AuditEvent();
        tail.setEntryHash("a".repeat(64));
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.of(tail));
        stubInsertInserts();

        service.persist(rejected("sess-1", 4), "1700000000001-0");

        // prev_hash (14th positional arg) chains to the prior tail's entry_hash.
        ArgumentCaptor<String> prevHash = ArgumentCaptor.forClass(String.class);
        verify(repository).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString(),
                any(), anyString(), any(), anyString(), anyString(), prevHash.capture(),
                anyString(), anyString(), anyInt());
        assertThat(prevHash.getValue()).isEqualTo("a".repeat(64));
    }

    @Test
    void duplicateOnExistenceProbeSkipsWithoutInsert() {
        when(repository.existsByDedupKey("CHUNK_ADMISSION_REJECTED:sess-1:3")).thenReturn(true);

        PersistOutcome outcome = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.DUPLICATE);
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
        verify(repository, never()).findTop1ByTenantIdOrderBySeqDesc(any());
    }

    @Test
    void duplicateRaceOnInsertReturnsZeroAffectedIsDuplicate() {
        // A racing duplicate slips past the existence probe; the atomic
        // ON CONFLICT (dedup_key) DO NOTHING inserts 0 rows → DUPLICATE, WITHOUT
        // raising (so on real PG the transaction is never aborted — proven in the
        // Testcontainers e2e). Here the 0-affected contract is asserted.
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        when(repository.insertOnConflictDoNothing(
                any(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString(),
                any(), anyString(), any(), anyString(), anyString(), any(), anyString(),
                anyString(), anyInt()))
                .thenReturn(0); // dedup_key already present

        PersistOutcome outcome = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.DUPLICATE);
    }

    @Test
    void nonDedupIntegrityViolationIsRethrownNotSwallowed() {
        // MUST-FIX #2: an integrity violation that is NOT a dedup_key collision is
        // NOT the ON CONFLICT target, so the native insert raises — it must
        // propagate (the consumer leaves the entry in the PEL for redelivery
        // rather than ACKing a lost event). It is NEVER reinterpreted as a
        // duplicate.
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        when(repository.insertOnConflictDoNothing(
                any(), anyLong(), anyString(), anyString(), any(), any(), any(), anyString(),
                any(), anyString(), any(), anyString(), anyString(), any(), anyString(),
                anyString(), anyInt()))
                .thenThrow(new DataIntegrityViolationException("null value in column violates not-null constraint"));

        assertThatThrownBy(() -> service.persist(rejected("sess-1", 3), "1700000000000-0"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void malformedRecordMissingTenantIsInvalidWithReason() {
        Map<String, String> bad = rejected("sess-1", 3);
        bad.remove("tenantId");

        PersistOutcome outcome = service.persist(bad, "1700000000000-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void malformedRecordNonNumericTenantIsInvalid() {
        // Now that tenantId is numeric, a non-numeric value (e.g. an accidental
        // UUID) is poison → INVALID (and would route to the DLQ).
        Map<String, String> bad = rejected("sess-1", 3);
        bad.put("tenantId", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        PersistOutcome outcome = service.persist(bad, "1-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void malformedRecordBadTimestampIsInvalid() {
        Map<String, String> bad = rejected("sess-1", 3);
        bad.put("timestampMs", "");

        assertThat(service.persist(bad, "1-0").result()).isEqualTo(PersistResult.INVALID);
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void farFutureTimestampIsInvalidBeforeAnyPersistence() {
        Map<String, String> bad = rejected("sess-future", 3);
        bad.put("timestampMs", Long.toString(Instant.parse("2026-06-16T10:06:00Z").toEpochMilli()));

        PersistOutcome outcome = service.persist(bad, "future-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void consentRevocationPersistsProjectionAndOutboxInSameServiceCall() {
        stubDurableGrant("user:7");
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        when(revocationRepository.findByEventKey(anyString())).thenReturn(Optional.empty());
        stubInsertInserts();

        PersistOutcome outcome = service.persist(revoked(2, "user:7"), "20-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.PERSISTED);
        ArgumentCaptor<RecordingConsentRevocation> revocation =
                ArgumentCaptor.forClass(RecordingConsentRevocation.class);
        ArgumentCaptor<ConsentEventOutbox> outbox =
                ArgumentCaptor.forClass(ConsentEventOutbox.class);
        verify(revocationRepository).saveAndFlush(revocation.capture());
        verify(outboxRepository).saveAndFlush(outbox.capture());

        assertThat(revocation.getValue().getEventKey())
                .isEqualTo("meeting.consent|22222222-2222-2222-2222-222222222222"
                        + "|meeting.consent.revoked|2");
        assertThat(revocation.getValue().getSourceHash()).matches("[0-9a-f]{64}");
        assertThat(revocation.getValue().getActorSubject()).isEqualTo("user:7");
        assertThat(outbox.getValue().getEventKey()).isEqualTo(revocation.getValue().getEventKey());
        assertThat(outbox.getValue().getPayload())
                .contains("\"eventType\":\"meeting.consent.revoked\"")
                .contains("\"consentRevision\":2")
                .doesNotContain("user:7")
                .doesNotContain("actor")
                .doesNotContain("transcript")
                .doesNotContain("audio");
        assertThat(outbox.getValue().getPayloadHash()).matches("[0-9a-f]{64}");
    }

    @Test
    void identicalConsentRevocationRedeliveryIsDuplicateWithNoSecondWrite() {
        stubDurableGrant("user:7");
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        when(revocationRepository.findByEventKey(anyString())).thenReturn(Optional.empty());
        stubInsertInserts();
        service.persist(revoked(2, "user:7"), "20-0");

        ArgumentCaptor<RecordingConsentRevocation> saved =
                ArgumentCaptor.forClass(RecordingConsentRevocation.class);
        verify(revocationRepository).saveAndFlush(saved.capture());
        ConsentEventOutbox existingOutbox = new ConsentEventOutbox();
        when(repository.existsByDedupKey(anyString())).thenReturn(true);
        when(revocationRepository.findByEventKey(anyString()))
                .thenReturn(Optional.of(saved.getValue()));
        when(outboxRepository.findByEventKey(anyString())).thenReturn(Optional.of(existingOutbox));

        PersistOutcome duplicate = service.persist(revoked(2, "user:7"), "21-0");

        assertThat(duplicate.result()).isEqualTo(PersistResult.DUPLICATE);
        verify(revocationRepository, times(1)).saveAndFlush(any(RecordingConsentRevocation.class));
        verify(outboxRepository, times(1)).saveAndFlush(any(ConsentEventOutbox.class));
    }

    @Test
    void consentRevocationRejectsFreeTextConsentVersionBeforePersistence() {
        Map<String, String> bad = revoked(2, "user:7");
        bad.put("consentVersion", "customer supplied free text");

        PersistOutcome outcome = service.persist(bad, "22-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(revocationRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void consentRevocationRejectsFreeTextReasonBeforePersistence() {
        Map<String, String> bad = revoked(2, "user:7");
        bad.put("reasonCode", "user included personal details");

        PersistOutcome outcome = service.persist(bad, "23-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(revocationRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void consentRevocationKeyCollisionWithDifferentActorIsRejected() {
        stubDurableGrant("user:99");
        RecordingConsentRevocation existing = new RecordingConsentRevocation();
        existing.setEventKey("meeting.consent|22222222-2222-2222-2222-222222222222"
                + "|meeting.consent.revoked|2");
        existing.setSourceHash("0".repeat(64));
        when(repository.existsByDedupKey(anyString())).thenReturn(true);
        when(revocationRepository.findByEventKey(anyString())).thenReturn(Optional.of(existing));

        PersistOutcome outcome = service.persist(revoked(2, "user:99"), "22-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.CONFLICT);
        assertThat(outcome.reason()).isEqualTo("IDEMPOTENCY_CONFLICT");
        verify(outboxRepository, never()).saveAndFlush(any());
    }

    @Test
    void consentRevocationWithoutGrantIsRetryableNotInvalid() {
        when(grantRepository.findByCaptureId(
                UUID.fromString("22222222-2222-2222-2222-222222222222")))
                .thenReturn(Optional.empty());

        PersistOutcome outcome = service.persist(revoked(2, "user:7"), "pending-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.RETRYABLE);
        assertThat(outcome.reason()).isEqualTo("CONSENT_DEPENDENCY_PENDING");
        verify(repository, never()).insertOnConflictDoNothing(
                any(), anyLong(), anyString(), any(), any(), any(), any(), any(),
                any(), any(), any(), anyString(), any(), any(), anyString(), anyString(), anyInt());
    }

    @Test
    void consentRevocationBeforeDurableGrantIsInvalid() {
        stubDurableGrant("user:7");
        Map<String, String> bad = revoked(2, "user:7");
        bad.put("timestampMs", Long.toString(Instant.parse("2026-06-16T09:58:59Z").toEpochMilli()));

        PersistOutcome outcome = service.persist(bad, "before-grant-0");

        assertThat(outcome.result()).isEqualTo(PersistResult.INVALID);
        assertThat(outcome.reason()).isEqualTo("INVALID_EVENT");
        verify(revocationRepository, never()).saveAndFlush(any());
        verify(outboxRepository, never()).saveAndFlush(any());
    }
}
