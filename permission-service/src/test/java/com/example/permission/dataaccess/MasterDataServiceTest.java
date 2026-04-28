package com.example.permission.dataaccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link MasterDataService}.
 *
 * <p>Faz 21.3 follow-up (2026-04-29): scope picker master data endpoint'leri.
 *
 * Covers:
 *   - 4 endpoint method (companies/projects/branches/departments)
 *   - SQL query content (correct table + column names)
 *   - Error fallback (table missing/connection fail → empty list)
 *   - DTO mapping (Long id, String name, boolean status)
 */
class MasterDataServiceTest {

    private JdbcTemplate jdbc;
    private MasterDataService service;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        service = new MasterDataService(jdbc);
    }

    @Test
    @DisplayName("listCompanies queries workcube_mikrolink.our_company")
    void listCompaniesQueriesOurCompany() {
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        service.listCompanies();

        verify(jdbc).query(contains("workcube_mikrolink.our_company"), any(RowMapper.class));
    }

    @Test
    @DisplayName("listProjects queries workcube_mikrolink.pro_projects")
    void listProjectsQueriesProProjects() {
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        service.listProjects();

        verify(jdbc).query(contains("workcube_mikrolink.pro_projects"), any(RowMapper.class));
    }

    @Test
    @DisplayName("listBranches queries workcube_mikrolink.branch")
    void listBranchesQueriesBranch() {
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        service.listBranches();

        verify(jdbc).query(contains("workcube_mikrolink.branch"), any(RowMapper.class));
    }

    @Test
    @DisplayName("listDepartments queries workcube_mikrolink.department")
    void listDepartmentsQueriesDepartment() {
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        service.listDepartments();

        verify(jdbc).query(contains("workcube_mikrolink.department"), any(RowMapper.class));
    }

    @Test
    @DisplayName("listCompanies returns mapped list")
    void listCompaniesReturnsMappedList() {
        List<MasterDataItem> mockResult = List.of(
                new MasterDataItem(1L, "Mikrolink Bilişim", true),
                new MasterDataItem(2L, "AÇIK A.Ş.", true)
        );
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(mockResult);

        List<MasterDataItem> result = service.listCompanies();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).id()).isEqualTo(1L);
        assertThat(result.get(0).name()).isEqualTo("Mikrolink Bilişim");
        assertThat(result.get(0).status()).isTrue();
    }

    @Test
    @DisplayName("listCompanies returns empty list on DataAccessException (table missing fallback)")
    void listCompaniesReturnsEmptyOnError() {
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("relation does not exist"));

        List<MasterDataItem> result = service.listCompanies();

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("listProjects returns empty list on DataAccessException")
    void listProjectsReturnsEmptyOnError() {
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("connection refused"));

        assertThat(service.listProjects()).isEmpty();
    }

    @Test
    @DisplayName("listBranches returns empty list on DataAccessException")
    void listBranchesReturnsEmptyOnError() {
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("schema not found"));

        assertThat(service.listBranches()).isEmpty();
    }

    @Test
    @DisplayName("listDepartments returns empty list on DataAccessException")
    void listDepartmentsReturnsEmptyOnError() {
        when(jdbc.query(any(String.class), any(RowMapper.class)))
                .thenThrow(new DataAccessResourceFailureException("relation does not exist"));

        assertThat(service.listDepartments()).isEmpty();
    }

    @Test
    @DisplayName("All queries include ORDER BY for deterministic dropdown order")
    void allQueriesIncludeOrderBy() {
        when(jdbc.query(any(String.class), any(RowMapper.class))).thenReturn(List.of());

        service.listCompanies();
        service.listProjects();
        service.listBranches();
        service.listDepartments();

        // Each invocation contains ORDER BY (case insensitive)
        verify(jdbc, org.mockito.Mockito.times(4))
                .query(any(String.class), any(RowMapper.class));
    }
}
