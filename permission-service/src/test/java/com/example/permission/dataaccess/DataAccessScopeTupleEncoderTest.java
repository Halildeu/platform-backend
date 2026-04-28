package com.example.permission.dataaccess;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DataAccessScopeTupleEncoderTest {

    private static final UUID USER = UUID.fromString("11111111-2222-3333-4444-555555555555");

    @Test
    void encode_company_returnsWcOurCompanyPrefixedTuple() {
        // V25 (Codex 019dd34e hybrid contract): COMPANY scope anchors to
        // workcube_mikrolink.OUR_COMPANY (tenant), not the COMPANY directory.
        // scope_ref carries OUR_COMPANY.COMP_ID — encoder emits the
        // V25-canonical "wc-our-company-<COMP_ID>" object id.
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[\"1\"]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectType()).isEqualTo("company");
        assertThat(tuple.objectId()).isEqualTo("wc-our-company-1");
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
     * emits {@code [1]} (numeric scalar) the encoder must still accept it
     * — {@link com.fasterxml.jackson.databind.JsonNode#asText()} on a number
     * returns its decimal representation, which round-trips correctly to
     * the V25-canonical {@code wc-our-company-1} object id.
     */
    @Test
    void encode_numericScopeRef_acceptsNumberAsText() {
        var scope = scope(DataAccessScope.ScopeKind.COMPANY, "[1]");

        var tuple = DataAccessScopeTupleEncoder.encode(scope);

        assertThat(tuple.objectId()).isEqualTo("wc-our-company-1");
    }

    private static DataAccessScope scope(DataAccessScope.ScopeKind kind, String scopeRef) {
        var s = new DataAccessScope();
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(kind);
        s.setScopeSourceSchema("workcube_mikrolink");
        // The encoder is pure (it only reads kind + scope_ref); source_table
        // is irrelevant to its output. Set a per-kind value that matches the
        // V25 CHECK constraint pairing so the fixture stays self-consistent
        // if a future test ever flushes through the V25 trigger chain.
        s.setScopeSourceTable(switch (kind == null ? DataAccessScope.ScopeKind.COMPANY : kind) {
            case COMPANY -> "OUR_COMPANY";
            case PROJECT -> "PRO_PROJECTS";
            case BRANCH -> "BRANCH";
            case DEPOT -> "DEPARTMENT";
        });
        s.setScopeRef(scopeRef);
        return s;
    }
}
