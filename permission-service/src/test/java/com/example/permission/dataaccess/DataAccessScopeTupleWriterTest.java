package com.example.permission.dataaccess;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class DataAccessScopeTupleWriterTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    private OpenFgaAuthzService authzService;
    private DataAccessScopeTupleWriter writer;

    @BeforeEach
    void setUp() {
        authzService = mock(OpenFgaAuthzService.class);
        writer = new DataAccessScopeTupleWriter(authzService);
    }

    @Test
    void writeScopeTuple_happyPath_callsAuthzServiceWithEncodedTuple() {
        // V25: COMPANY scope_ref = OUR_COMPANY.COMP_ID (e.g. "1") →
        // encoder emits wc-our-company-1. Codex 019dd34e hybrid contract.
        var scope = scope(1L, DataAccessScope.ScopeKind.COMPANY, "[\"1\"]");

        writer.writeScopeTuple(scope);

        ArgumentCaptor<String> userId = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> relation = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> objectType = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> objectId = ArgumentCaptor.forClass(String.class);
        verify(authzService).writeTuple(
                userId.capture(), relation.capture(), objectType.capture(), objectId.capture());

        assertThat(userId.getValue()).isEqualTo(USER.toString());
        assertThat(relation.getValue()).isEqualTo("viewer");
        assertThat(objectType.getValue()).isEqualTo("company");
        assertThat(objectId.getValue()).isEqualTo("wc-our-company-1");
    }

    @Test
    void writeScopeTuple_depotKind_writesWarehouseTypeWithDepartmentPrefix() {
        var scope = scope(2L, DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]");

        writer.writeScopeTuple(scope);

        verify(authzService).writeTuple(
                USER.toString(), "viewer", "warehouse", "wc-department-3792");
    }

    @Test
    void deleteScopeTuple_callsAuthzServiceDeleteWithEncodedTuple() {
        var scope = scope(3L, DataAccessScope.ScopeKind.PROJECT, "[\"1204\"]");

        writer.deleteScopeTuple(scope);

        verify(authzService).deleteTuple(
                USER.toString(), "viewer", "project", "wc-project-1204");
    }

    @Test
    void writeScopeTuple_authzServiceIdempotentNoException_swallowsAndCompletes() {
        // OpenFgaAuthzService swallows already-exists errors itself (OI-03);
        // a successful no-op invocation must NOT throw at the writer layer.
        var scope = scope(4L, DataAccessScope.ScopeKind.BRANCH, "[\"7\"]");

        writer.writeScopeTuple(scope);

        verify(authzService).writeTuple(USER.toString(), "viewer", "branch", "wc-branch-7");
    }

    @Test
    void writeScopeTuple_encoderError_propagatesIllegalArgumentException_noAuthzCall() {
        var scope = scope(5L, DataAccessScope.ScopeKind.COMPANY, "not-json");

        assertThatThrownBy(() -> writer.writeScopeTuple(scope))
                .isInstanceOf(IllegalArgumentException.class);

        verifyNoInteractions(authzService);
    }

    @Test
    void writeScopeTuple_authzServiceFailure_propagatesRuntimeException() {
        var scope = scope(6L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]");
        doThrow(new RuntimeException("openfga unreachable"))
                .when(authzService).writeTuple(any(), any(), any(), any());

        assertThatThrownBy(() -> writer.writeScopeTuple(scope))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("openfga unreachable");
    }

    private static DataAccessScope scope(Long id, DataAccessScope.ScopeKind kind, String scopeRef) {
        var s = new DataAccessScope();
        s.setId(id);
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(kind);
        s.setScopeSourceSchema("workcube_mikrolink");
        s.setScopeSourceTable("COMPANY");
        s.setScopeRef(scopeRef);
        return s;
    }
}
