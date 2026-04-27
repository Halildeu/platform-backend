package com.example.permission.dataaccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
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

class AccessScopeServiceTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID GRANTED_BY = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private DataAccessScopeRepository repository;
    private DataAccessScopeTupleWriter tupleWriter;
    private AccessScopeService service;

    @BeforeEach
    void setUp() {
        repository = mock(DataAccessScopeRepository.class);
        tupleWriter = mock(DataAccessScopeTupleWriter.class);
        service = new AccessScopeService(repository, tupleWriter);
    }

    @Test
    void grant_happyPath_savesAndWritesTuple() {
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenAnswer(inv -> {
            DataAccessScope arg = inv.getArgument(0);
            arg.setId(42L);
            return arg;
        });

        DataAccessScope result = service.grant(
                USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", GRANTED_BY);

        assertThat(result.getId()).isEqualTo(42L);
        assertThat(result.getScopeSourceTable()).isEqualTo("COMPANY");
        assertThat(result.getScopeSourceSchema()).isEqualTo("workcube_mikrolink");
        assertThat(result.getGrantedBy()).isEqualTo(GRANTED_BY);
        assertThat(result.getGrantedAt()).isNotNull();
        verify(tupleWriter, times(1)).writeScopeTuple(result);
    }

    @Test
    void grant_depotKind_setsDEPARTMENTSourceTable() {
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenAnswer(inv -> inv.getArgument(0));

        DataAccessScope result = service.grant(
                USER, 1L, DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]", GRANTED_BY);

        assertThat(result.getScopeSourceTable())
                .as("DEPOT must map to DEPARTMENT per Faz 21.A decision")
                .isEqualTo("DEPARTMENT");
    }

    @Test
    void grant_uniqueViolation_throwsScopeAlreadyGranted_andSkipsTupleWrite() {
        // Hibernate wraps PG's unique_violation (SQLState 23505) and exposes
        // the constraint name; iter-1 routing matches on EITHER signal.
        var sqlEx = new java.sql.SQLException(
                "ERROR: duplicate key value violates unique constraint", "23505");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "constraint", sqlEx, "uq_scope_active_assignment");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("dup", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class)
                .hasMessageContaining("user=" + USER);

        verify(tupleWriter, never()).writeScopeTuple(any());
    }

    @Test
    void grant_lineageViolation_throwsScopeValidation_andSkipsTupleWrite() {
        // V19 trigger RAISE EXCEPTION → PG SQLState P0001.
        var sqlEx = new java.sql.SQLException(
                "ERROR: data_access.scope: invalid scope_ref 9999 for kind company "
                        + "/ source_table COMPANY (no matching row in workcube_mikrolink.* with that source_pk)",
                "P0001");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "trigger raised", sqlEx, null);
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("trigger", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"9999\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class)
                .hasMessageContaining("lineage guard");

        verify(tupleWriter, never()).writeScopeTuple(any());
    }

    @Test
    void grant_unknownDataIntegrityViolation_propagatesOriginal() {
        var dbCause = new RuntimeException("ERROR: some other constraint failed");
        DataIntegrityViolationException original =
                new DataIntegrityViolationException("other", dbCause);
        when(repository.saveAndFlush(any(DataAccessScope.class))).thenThrow(original);

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", GRANTED_BY))
                .isSameAs(original);

        verify(tupleWriter, never()).writeScopeTuple(any());
    }

    @Test
    void revoke_happyPath_setsRevokedAtAndDeletesTuple() {
        var existing = activeScope(7L);
        when(repository.findById(7L)).thenReturn(Optional.of(existing));
        when(repository.save(any(DataAccessScope.class))).thenAnswer(inv -> inv.getArgument(0));

        DataAccessScope result = service.revoke(7L, GRANTED_BY);

        assertThat(result.getRevokedAt()).isNotNull();
        assertThat(result.getRevokedBy()).isEqualTo(GRANTED_BY);
        assertThat(result.isActive()).isFalse();

        ArgumentCaptor<DataAccessScope> deleted = ArgumentCaptor.forClass(DataAccessScope.class);
        verify(tupleWriter).deleteScopeTuple(deleted.capture());
        assertThat(deleted.getValue().getId()).isEqualTo(7L);
    }

    @Test
    void revoke_alreadyRevoked_throwsScopeAlreadyRevoked_andSkipsTupleDelete() {
        var existing = activeScope(8L);
        existing.setRevokedAt(Instant.parse("2026-04-26T10:00:00Z"));
        when(repository.findById(8L)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.revoke(8L, GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyRevokedException.class);

        verify(tupleWriter, never()).deleteScopeTuple(any());
        verify(repository, never()).save(any());
    }

    @Test
    void revoke_notFound_throwsScopeNotFound() {
        when(repository.findById(999L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke(999L, GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeNotFoundException.class);

        verify(tupleWriter, never()).deleteScopeTuple(any());
    }

    @Test
    void grant_pgUniqueViolationByConstraintName_throwsScopeAlreadyGranted() {
        // Constraint-name-only path: SQLState absent, but the constraint name
        // matches uq_scope_active_assignment → routing still fires.
        var sqlEx = new java.sql.SQLException("dup", (String) null);
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "constraint", sqlEx, "uq_scope_active_assignment");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("dup", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeAlreadyGrantedException.class);

        verify(tupleWriter, never()).writeScopeTuple(any());
    }

    @Test
    void grant_pgCheckViolation_throwsScopeValidation() {
        // SQLState 23514 = check_violation (e.g. scope_kind_source_table_consistent).
        var sqlEx = new java.sql.SQLException("check failed", "23514");
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "check constraint", sqlEx, "scope_kind_source_table_consistent");
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("check", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        verify(tupleWriter, never()).writeScopeTuple(any());
    }

    @Test
    void grant_pgTriggerRaiseException_throwsScopeValidation_viaMessageFallback() {
        // SQLState absent, constraintName absent, but the message contains
        // validate_scope_ref → message-fallback path catches it. Guards
        // against a future PG locale change that drops SQLState on raised
        // exceptions.
        var sqlEx = new java.sql.SQLException(
                "ERROR: invalid scope_ref called from validate_scope_ref()", (String) null);
        var hcve = new org.hibernate.exception.ConstraintViolationException(
                "trigger", sqlEx, null);
        when(repository.saveAndFlush(any(DataAccessScope.class)))
                .thenThrow(new DataIntegrityViolationException("trigger", hcve));

        assertThatThrownBy(() -> service.grant(
                        USER, 1L, DataAccessScope.ScopeKind.COMPANY, "[\"9999\"]", GRANTED_BY))
                .isInstanceOf(AccessScopeException.ScopeValidationException.class);

        verify(tupleWriter, never()).writeScopeTuple(any());
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
        s.setScopeSourceTable("COMPANY");
        s.setScopeRef("[\"1001\"]");
        s.setGrantedAt(Instant.parse("2026-04-25T10:00:00Z"));
        return s;
    }
}
