package com.example.auditconsumer.service;

import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.repository.AuditEventRepository;
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
    private AuditEventPersistenceService service;

    @BeforeEach
    void setUp() {
        repository = mock(AuditEventRepository.class);
        AuditChainLock noOpLock = tenantId -> { /* no-op */ };
        Clock fixed = Clock.fixed(Instant.parse("2026-06-16T10:00:00Z"), ZoneOffset.UTC);
        service = new AuditEventPersistenceService(repository, noOpLock, fixed);
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
        fields.put("timestampMs", "1700000000000");
        return fields;
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
        assertThat(outcome.reason()).contains("tenantId");
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
        assertThat(outcome.reason()).contains("tenantId");
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
}
