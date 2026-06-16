package com.example.auditconsumer.service;

import com.example.auditconsumer.audit.AuditChainLock;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — {@link AuditEventPersistenceService}
 * unit tests (mocked repository + no-op lock; mapping + idempotency + chain
 * composition without a DB). The genuine immutable persist + advisory lock +
 * tamper-detect are proven on real engines by
 * {@code AuditPipelineEndToEndPostgresIntegrationTest}.
 */
class AuditEventPersistenceServiceTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

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
        fields.put("tenantId", TENANT.toString());
        fields.put("userId", "7");
        fields.put("chunkSeq", Long.toString(chunkSeq));
        fields.put("httpStatus", "429");
        fields.put("rejectionCode", "QUEUE_FULL");
        fields.put("retryAfterSeconds", ""); // absent nullable → empty string
        fields.put("correlationId", "corr-1");
        fields.put("timestampMs", "1700000000000");
        return fields;
    }

    @Test
    void mapsFieldsAndPersistsNewEventAsGenesis() {
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());

        PersistResult result = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(result).isEqualTo(PersistResult.PERSISTED);
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        AuditEvent saved = captor.getValue();
        assertThat(saved.getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getEventType()).isEqualTo("CHUNK_ADMISSION_REJECTED");
        assertThat(saved.getSessionId()).isEqualTo("sess-1");
        assertThat(saved.getChunkSeq()).isEqualTo(3L);
        assertThat(saved.getHttpStatus()).isEqualTo(429);
        assertThat(saved.getRetryAfterSeconds()).isNull(); // empty → null
        assertThat(saved.getDedupKey()).isEqualTo("CHUNK_ADMISSION_REJECTED:sess-1:3");
        assertThat(saved.getStreamEntryId()).isEqualTo("1700000000000-0");
        // Genesis: prev null, entry hash present.
        assertThat(saved.getPrevHash()).isNull();
        assertThat(saved.getEntryHash()).hasSize(64).matches("[0-9a-f]{64}");
        assertThat(saved.getEntryHashAlg()).isEqualTo("SHA-256");
        assertThat(saved.getEntryHashVersion()).isEqualTo(1);
    }

    @Test
    void chainsToPriorTailHash() {
        AuditEvent tail = new AuditEvent();
        tail.setEntryHash("a".repeat(64));
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.of(tail));

        service.persist(rejected("sess-1", 4), "1700000000001-0");

        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getPrevHash()).isEqualTo("a".repeat(64));
    }

    @Test
    void duplicateOnExistenceProbeSkipsWithoutSave() {
        when(repository.existsByDedupKey("CHUNK_ADMISSION_REJECTED:sess-1:3")).thenReturn(true);

        PersistResult result = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(result).isEqualTo(PersistResult.DUPLICATE);
        verify(repository, never()).save(any());
        verify(repository, never()).findTop1ByTenantIdOrderBySeqDesc(any());
    }

    @Test
    void duplicateOnUniqueConstraintRaceIsTreatedAsDuplicate() {
        when(repository.existsByDedupKey(anyString())).thenReturn(false);
        when(repository.findTop1ByTenantIdOrderBySeqDesc(TENANT)).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup dedup_key"));

        PersistResult result = service.persist(rejected("sess-1", 3), "1700000000000-0");

        assertThat(result).isEqualTo(PersistResult.DUPLICATE);
    }

    @Test
    void malformedRecordMissingTenantIsDroppedAsInvalid() {
        Map<String, String> bad = rejected("sess-1", 3);
        bad.remove("tenantId");

        PersistResult result = service.persist(bad, "1700000000000-0");

        assertThat(result).isEqualTo(PersistResult.INVALID);
        verify(repository, never()).save(any());
    }

    @Test
    void malformedRecordNonUuidTenantIsDroppedAsInvalid() {
        Map<String, String> bad = rejected("sess-1", 3);
        bad.put("tenantId", "not-a-uuid");

        assertThat(service.persist(bad, "1-0")).isEqualTo(PersistResult.INVALID);
        verify(repository, never()).save(any());
    }

    @Test
    void malformedRecordBadTimestampIsDroppedAsInvalid() {
        Map<String, String> bad = rejected("sess-1", 3);
        bad.put("timestampMs", "");

        assertThat(service.persist(bad, "1-0")).isEqualTo(PersistResult.INVALID);
        verify(repository, never()).save(any());
    }
}
