package com.example.permission.dataaccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 21.3 PR-G refactor of PR-D's direct-write contract:
 * grant/revoke now insert {@link DataAccessScopeOutboxEntry} rows in the
 * same TX as the {@code data_access.scope} write; no direct OpenFGA call.
 * The poller ({@link OutboxPoller}) consumes the outbox asynchronously.
 */
class AccessScopeServiceTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID GRANTED_BY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private DataAccessScopeRepository repository;
    private DataAccessScopeOutboxRepository outboxRepository;
    private AccessScopeService service;

    @BeforeEach
    void setUp() {
        repository = mock(DataAccessScopeRepository.class);
        outboxRepository = mock(DataAccessScopeOutboxRepository.class);
        when(outboxRepository.save(any(DataAccessScopeOutboxEntry.class)))
                .thenAnswer(inv -> {
                    DataAccessScopeOutboxEntry e = inv.getArgument(0);
                    if (e.getId() == null) e.setId(900L);
                    return e;
                });
        service = new AccessScopeService(repository, outboxRepository);
    }

    @Test
    void grant_happyPath_savesScopeAndEnqueuesGrantOutbox() {
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenAnswer(inv -> {
            DataAccessScope arg = inv.getArgument(0);
            arg.setId(42L);
            return arg;
        });

        // V25 (Codex 019dd34e hybrid contract): COMPANY scope_ref is now the
        // OUR_COMPANY.COMP_ID (e.g. "1") — the V25 CHECK constraint pairs
        // scope_kind=company with scope_source_table=OUR_COMPANY (was COMPANY),
        // and the encoder emits company:wc-our-company-1 (was wc-company-1001).
        AccessScopeService.ScopeMutationResult result = service.grant(
                USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", GRANTED_BY);

        DataAccessScope scope = result.scope();
        DataAccessScopeOutboxEntry outbox = result.outboxEntry();

        assertThat(scope.getId()).isEqualTo(42L);
        assertThat(scope.getScopeSourceTable()).isEqualTo("OUR_COMPANY");
        assertThat(scope.getScopeSourceSchema()).isEqualTo("workcube_mikrolink");
        assertThat(scope.getGrantedBy()).isEqualTo(GRANTED_BY);
        assertThat(scope.getGrantedAt()).isNotNull();

        assertThat(outbox.getScopeId()).isEqualTo(42L);
        assertThat(outbox.getAction()).isEqualTo(DataAccessScopeOutboxEntry.Action.GRANT);
        assertThat(outbox.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PENDING);
        assertThat(outbox.getNextAttemptAt()).isNotNull();
        assertThat(outbox.getCreatedAt()).isNotNull();

        // V23 typed tuple identity columns (Codex 019dd0e0 BLOCKER 2);
        // V25 object-id namespace flip: wc-our-company-<COMP_ID>.
        assertThat(outbox.getTupleUser()).isEqualTo("user:" + USER);
        assertThat(outbox.getTupleRelation()).isEqualTo("viewer");
        assertThat(outbox.getTupleObject()).isEqualTo("company:wc-our-company-1");

        // JSONB payload keeps the same shape for downstream debug/audit consumers,
        // with V23 picking up the composite "object" key alongside the
        // pre-existing objectType/objectId split.
        @SuppressWarnings("unchecked")
        Map<String, Object> tuple = (Map<String, Object>) outbox.getPayload().get("tuple");
        assertThat(tuple.get("user")).isEqualTo("user:" + USER);
        assertThat(tuple.get("relation")).isEqualTo("viewer");
        assertThat(tuple.get("object")).isEqualTo("company:wc-our-company-1");
        assertThat(tuple.get("objectType")).isEqualTo("company");
        assertThat(tuple.get("objectId")).isEqualTo("wc-our-company-1");
        assertThat(outbox.getPayload().get("scopeKind")).isEqualTo("COMPANY");
        assertThat(outbox.getPayload().get("scopeRef")).isEqualTo("[\"1\"]");

        verify(outboxRepository, times(1)).save(any(DataAccessScopeOutboxEntry.class));
    }

    @Test
    void grant_depotKind_setsDEPARTMENTSourceTableAndWarehouseTuple() {
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenAnswer(inv -> {
            DataAccessScope arg = inv.getArgument(0);
            arg.setId(43L);
            return arg;
        });

        AccessScopeService.ScopeMutationResult result = service.grant(
                USER, 1L, DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]", GRANTED_BY);

        assertThat(result.scope().getScopeSourceTable())
                .as("DEPOT must map to DEPARTMENT per Faz 21.A decision")
                .isEqualTo("DEPARTMENT");

        // V23 typed tuple identity columns: depot → warehouse object type +
        // composite "type:id" tuple_object format.
        DataAccessScopeOutboxEntry outbox = result.outboxEntry();
        assertThat(outbox.getTupleObject())
                .as("DEPOT scope_kind → warehouse:wc-department-* per ADR-0008 § Naming + V23 composite format")
                .isEqualTo("warehouse:wc-department-3792");

        @SuppressWarnings("unchecked")
        Map<String, Object> tuple = (Map<String, Object>) outbox.getPayload().get("tuple");
        assertThat(tuple.get("objectType")).isEqualTo("warehouse");
        assertThat(tuple.get("objectId")).isEqualTo("wc-department-3792");
        assertThat(tuple.get("object")).isEqualTo("warehouse:wc-department-3792");
    }

    @Test
    void grant_uniqueViolation_throwsScopeAlreadyGranted_andSkipsOutboxInsert() {
        var sqlEx = new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint", "23505");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "constraint", sqlEx, "uq_scope_active_assignment");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("dup", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class)
                .hasMessageContaining("user=" + USER);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void grant_lineageViolation_throwsScopeValidation_andSkipsOutboxInsert() {
        var sqlEx = new java.sql.SQLException(
                "ERROR: data_access.scope: invalid scope_ref 9999 ... validate_scope_ref",
                "P0001");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "trigger raised", sqlEx, null);
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("trigger", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"99\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class)
                .hasMessageContaining("lineage guard");

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void grant_unknownDataIntegrityViolation_propagatesOriginal() {
        var dbCause = new RuntimeException("ERROR: some other constraint failed");
        DataIntegrityViolationException original =
                new DataIntegrityViolationException("other", dbCause);
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenThrow(original);

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", GRANTED_BY))
                .isSameAs(original);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void revoke_happyPath_setsRevokedAtAndEnqueuesRevokeOutbox() {
        var existing = activeScope(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(DataAccessScope.class))).thenAnswer(inv -> inv.getArgument(0));

        AccessScopeService.ScopeMutationResult result = service.revoke(7L, GRANTED_BY);
        DataAccessScope scope = result.scope();
        DataAccessScopeOutboxEntry outbox = result.outboxEntry();

        assertThat(scope.getRevokedAt()).isNotNull();
        assertThat(scope.getRevokedBy()).isEqualTo(GRANTED_BY);
        assertThat(scope.isActive()).isFalse();

        ArgumentCaptor<DataAccessScopeOutboxEntry> capt = ArgumentCaptor.forClass(DataAccessScopeOutboxEntry.class);
        verify(outboxRepository).save(capt.capture());
        DataAccessScopeOutboxEntry persisted = capt.getValue();
        assertThat(persisted.getScopeId()).isEqualTo(7L);
        assertThat(persisted.getAction()).isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);
        assertThat(persisted.getStatus()).isEqualTo(DataAccessScopeOutboxEntry.Status.PENDING);
        assertThat(outbox.getAction()).isEqualTo(DataAccessScopeOutboxEntry.Action.REVOKE);
    }

    @Test
    void revoke_alreadyRevoked_throwsScopeAlreadyRevoked_andSkipsBothWrites() {
        var existing = activeScope(8L);
        existing.setRevokedAt(Instant.parse("2026-04-26T10:00:00Z"));
        when(repository.findById(8L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.revoke(8L, GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyRevokedException.class);

        verify(outboxRepository, never()).save(any());
        verify(repository, never()).save(any());
    }

    @Test
    void revoke_notFound_throwsScopeNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(999L, GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeNotFoundException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void grant_pgUniqueViolationByConstraintName_throwsScopeAlreadyGranted() {
        var sqlEx = new java.sql.SQLException("dup", (String) null);
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "constraint", sqlEx, "uq_scope_active_assignment");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("dup", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void grant_pgCheckViolation_throwsScopeValidation() {
        var sqlEx = new java.sql.SQLException("check failed", "23514");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "check constraint", sqlEx, "scope_kind_source_table_consistent");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("check", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void grant_pgTriggerRaiseException_throwsScopeValidation_viaMessageFallback() {
        var sqlEx = new java.sql.SQLException(
                "ERROR: invalid scope_ref called from validate_scope_ref()", (String) null);
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "trigger", sqlEx, null);
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("trigger", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"99\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        verify(outboxRepository, never()).save(any());
    }

    @Test
    void listActiveScopes_returnsRepositoryResult() {
        var scope1 = activeScope(1L);
        var scope2 = activeScope(2L);
        when(repository.findByUserIdAndOrgIdAndRevokedAtIsNull(USER, 1L))
                .thenReturn(List.of(scope1, scope2));

        List<DataAccessScope> result = service.listActiveScopes(USER, 1L);

        assertThat(result).extracting(DataAccessScope::getId).containsExactly(1L, 2L);
    }

    private static DataAccessScope activeScope(Long id) {
        var s = new DataAccessScope();
        s.setId(id);
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(DataAccessScope.ScopeKind.COMPANY);
        s.setScopeSourceSchema("workcube_mikrolink");
        // V25 contract: company ↔ OUR_COMPANY pairing, scope_ref is COMP_ID.
        s.setScopeSourceTable("OUR_COMPANY");
        s.setScopeRef("[\"1\"]");
        s.setGrantedAt(Instant.parse("2026-04-25T10:00:00Z"));
        return s;
    }
}
