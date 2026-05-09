package com.example.schema.service;

import com.example.schema.dto.MasterDataItemDto;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * PR-BE-15 (2026-05-09): MasterDataReadService unit tests.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@link #unknownKindThrows()} — allowlist guard</li>
 *   <li>{@link #companiesProjectsNullParents()} — top-level OUR_COMPANY rows
 *       map parent FKs to NULL even when SQL returns 0 (rs.getLong default).</li>
 *   <li>{@link #projectsCarryParentCompanyAndBranch()} — JOIN-derived
 *       OUR_COMPANY_ID + direct BRANCH_ID surface in the DTO.</li>
 *   <li>{@link #departmentsCarryParentCompanyAndBranch()} — direct
 *       OUR_COMPANY_ID + BRANCH_ID surface in the DTO.</li>
 *   <li>{@link #parentNullPreservedViaWasNull()} — wasNull() guard
 *       distinguishes real id 0 from missing FK.</li>
 * </ul>
 *
 * <p>Mock JDBC strategy: stub
 * {@link NamedParameterJdbcTemplate#query(String, java.util.Map, RowMapper)}
 * to invoke the captured RowMapper against a hand-rolled ResultSet
 * mock. Avoids needing an in-memory MSSQL fake; all SQL syntax is
 * trusted to the integration suite.
 */
class MasterDataReadServiceTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);

    private MasterDataReadService newService() {
        return new MasterDataReadService(jdbc, "workcube_mikrolink", 50000);
    }

    @Test
    void unknownKindThrows() {
        var service = newService();
        assertThatThrownBy(() -> service.list("unknown-kind"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown master-data kind");
    }

    @Test
    void companiesProjectsNullParents() throws SQLException {
        var service = newService();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(7L);
        when(rs.getString("code")).thenReturn("MB");
        when(rs.getString("name")).thenReturn("Mikrolink Bilişim");
        when(rs.getLong("parentCompanyId")).thenReturn(0L);
        when(rs.getLong("parentBranchId")).thenReturn(0L);
        when(rs.getLong("parentProjectId")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(true);
        when(rs.getBoolean("status")).thenReturn(true);

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<MasterDataItemDto> items = service.list("companies");

        assertThat(items).hasSize(1);
        var only = items.get(0);
        assertThat(only.id()).isEqualTo(7L);
        assertThat(only.code()).isEqualTo("MB");
        assertThat(only.name()).isEqualTo("Mikrolink Bilişim");
        assertThat(only.parentCompanyId()).isNull();
        assertThat(only.parentBranchId()).isNull();
        assertThat(only.parentProjectId()).isNull();
        assertThat(only.status()).isTrue();
    }

    @Test
    void projectsCarryParentCompanyAndBranch() throws SQLException {
        var service = newService();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(42L);
        when(rs.getString("code")).thenReturn("P-002");
        when(rs.getString("name")).thenReturn("Mikrolink ERP");
        when(rs.getLong("parentCompanyId")).thenReturn(7L); // OUR_COMPANY id
        when(rs.getLong("parentBranchId")).thenReturn(15L); // BRANCH id
        when(rs.getLong("parentProjectId")).thenReturn(0L); // not project-scoped
        // wasNull() is interrogated AFTER each getLong; project parents
        // are real values, but parentProjectId is NULL.
        when(rs.wasNull()).thenReturn(false, false, true);
        when(rs.getBoolean("status")).thenReturn(true);

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<MasterDataItemDto> items = service.list("projects");

        assertThat(items).hasSize(1);
        var project = items.get(0);
        assertThat(project.id()).isEqualTo(42L);
        assertThat(project.parentCompanyId()).isEqualTo(7L);
        assertThat(project.parentBranchId()).isEqualTo(15L);
        assertThat(project.parentProjectId()).isNull();
    }

    @Test
    void departmentsCarryParentCompanyAndBranch() throws SQLException {
        var service = newService();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(99L);
        when(rs.getString("code")).thenReturn("D-CES");
        when(rs.getString("name")).thenReturn("CES Ana Depo");
        when(rs.getLong("parentCompanyId")).thenReturn(3L); // direct OUR_COMPANY
        when(rs.getLong("parentBranchId")).thenReturn(10L); // direct BRANCH
        when(rs.getLong("parentProjectId")).thenReturn(0L);
        when(rs.wasNull()).thenReturn(false, false, true);
        when(rs.getBoolean("status")).thenReturn(true);

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<MasterDataItemDto> items = service.list("departments");

        assertThat(items).hasSize(1);
        var dept = items.get(0);
        assertThat(dept.parentCompanyId()).isEqualTo(3L);
        assertThat(dept.parentBranchId()).isEqualTo(10L);
        assertThat(dept.parentProjectId()).isNull();
    }

    @Test
    void parentNullPreservedViaWasNull() throws SQLException {
        // PR-BE-15: rs.getLong returns 0 for SQL NULL. wasNull() guard
        // distinguishes "real id 0" (rare for FKs but valid) from
        // "no parent". Project with company FK = 0 should preserve
        // 0 (legitimate, even if unusual); project with NULL FK
        // should map to null.
        var service = newService();

        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong("id")).thenReturn(50L);
        when(rs.getString("code")).thenReturn(null);
        when(rs.getString("name")).thenReturn("Edge case");
        when(rs.getLong("parentCompanyId")).thenReturn(0L); // SQL NULL
        when(rs.getLong("parentBranchId")).thenReturn(0L);  // real 0 (legitimate)
        when(rs.getLong("parentProjectId")).thenReturn(0L); // SQL NULL
        // wasNull returns true for parentCompany, false for parentBranch
        // (legitimate 0), true for parentProject.
        when(rs.wasNull()).thenReturn(true, false, true);
        when(rs.getBoolean("status")).thenReturn(true);

        when(jdbc.query(anyString(), anyMap(), any(RowMapper.class)))
                .thenAnswer(inv -> {
                    RowMapper<?> mapper = inv.getArgument(2);
                    return List.of(mapper.mapRow(rs, 0));
                });

        List<MasterDataItemDto> items = service.list("departments");

        assertThat(items).hasSize(1);
        var item = items.get(0);
        assertThat(item.parentCompanyId()).as("SQL NULL → null").isNull();
        assertThat(item.parentBranchId()).as("real 0 → 0L preserved").isEqualTo(0L);
        assertThat(item.parentProjectId()).as("SQL NULL → null").isNull();
    }

    @Test
    void unknownKindReturnsAllowlistError() {
        var service = newService();
        // Allowlist enforcement — kind names not in KIND_MAP throw before
        // any SQL runs (injection guard).
        assertThatThrownBy(() -> service.list("DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list("'; SELECT 1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.list(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
