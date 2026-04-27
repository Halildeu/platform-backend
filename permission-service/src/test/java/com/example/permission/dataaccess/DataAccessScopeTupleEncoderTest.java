package com.example.permission.dataaccess;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAccessScopeTupleEncoderTest {

    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void encode_company_returnsWcCompanyPrefixedTuple() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectType()).isEqualTo("company");
        assertThat(tuple.objectId()).isEqualTo("wc-company-1001");
        assertThat(tuple.relation()).isEqualTo("viewer");
        assertThat(tuple.userId()).isEqualTo(USER.toString());
    }

    @Test
    void encode_project_returnsWcProjectPrefixedTuple() {
        var scope = scope(DataAccessScope.ScopeKind.PROJECT, "[\"1204\"]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectType()).isEqualTo("project");
        assertThat(tuple.objectId()).isEqualTo("wc-project-1204");
        assertThat(tuple.relation()).isEqualTo("viewer");
    }

    @Test
    void encode_branch_returnsWcBranchPrefixedTuple() {
        var scope = scope(DataAccessScope.ScopeKind.BRANCH, "[\"7\"]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectType()).isEqualTo("branch");
        assertThat(tuple.objectId()).isEqualTo("wc-branch-7");
        assertThat(tuple.relation()).isEqualTo("viewer");
    }

    /**
     * Critical regression guard: PG {@code scope_kind = 'depot'} MUST map to
     * OpenFGA object type {@code warehouse} (NOT {@code depot}). ADR-0008 §
     * Naming. Object id prefix is {@code wc-department-} (Faz 21.A).
     */
    @Test
    void encode_depot_mapsToWarehouseObjectType_withWcDepartmentPrefix() {
        var scope = scope(DataAccessScope.ScopeKind.DEPOT, "[\"3792\"]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectType())
                .as("PG depot must map to OpenFGA warehouse type per ADR-0008 § Naming")
                .isEqualTo("warehouse");
        assertThat(tuple.objectId()).isEqualTo("wc-department-3792");
        assertThat(tuple.relation()).isEqualTo("viewer");
    }

    @Test
    void encode_malformedScopeRef_throwsIllegalArgumentException() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "not-json-{");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not valid JSON");
    }

    @Test
    void encode_emptyScopeRefArray_throwsIllegalArgumentException() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[]");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-empty JSON array");
    }

    @Test
    void encode_nullScopeKind_throwsIllegalArgumentException() {
        var scope = scope(null, "[\"1001\"]");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scopeKind");
    }

    @Test
    void encode_nestedArrayScopeRef_throwsIllegalArgumentException() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[[\"1001\"]]");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar");
    }

    @Test
    void encode_nestedObjectScopeRef_throwsIllegalArgumentException() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[{\"id\":\"1001\"}]");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scalar");
    }

    @Test
    void encode_blankStringScopeRef_throwsIllegalArgumentException() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[\"\"]");

        assertThatThrownBy(() -> DataAccessScopeTupleEncoder.encode(scope))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
    }

    /**
     * PG scope_ref contract is a string array, but if a JSON producer ever
     * emits {@code [1001]} (numeric scalar) the encoder must still accept it
     * — {@link com.fasterxml.jackson.databind.JsonNode#asText()} on a number
     * returns its decimal representation, which round-trips correctly to
     * the {@code wc-company-1001} object id.
     */
    @Test
    void encode_numericScopeRef_acceptsNumberAsText() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[1001]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectId()).isEqualTo("wc-company-1001");
    }

    private static DataAccessScope scope(DataAccessScope.ScopeKind kind, String scopeRef) {
        var s = new DataAccessScope();
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(kind);
        s.setScopeSourceSchema("workcube_mikrolink");
        s.setScopeSourceTable("COMPANY");
        s.setScopeRef(scopeRef);
        return s;
    }
}
