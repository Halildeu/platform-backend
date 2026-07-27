package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * T-SQL regression coverage for the company project picker.
 *
 * <p>The live Workcube catalog can attach a project to its customer/current
 * account company while the selected owner tenant is encoded by the yearly
 * accounting schema. This test proves that the picker combines both sources
 * without allowing a similarly prefixed tenant schema to bleed into company
 * scope.
 */
@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class ProjectOptionsRepositoryMssqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static ProjectOptionsRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName(MSSQL.getDriverClassName());
        ds.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        ds.setUsername(MSSQL.getUsername());
        ds.setPassword(MSSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        for (String schema : List.of(
                "workcube_mikrolink",
                "workcube_mikrolink_2025_35",
                "workcube_mikrolink_2026_35",
                "workcube_mikrolink_2026_350")) {
            jdbc.execute("EXEC('CREATE SCHEMA [" + schema + "]')");
        }

        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[OUR_COMPANY] (
                    COMP_ID INT NOT NULL PRIMARY KEY
                )
                """);
        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[COMPANY] (
                    COMPANY_ID INT NOT NULL PRIMARY KEY,
                    OUR_COMPANY_ID INT NULL
                )
                """);
        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[PRO_PROJECTS] (
                    PROJECT_ID INT NOT NULL PRIMARY KEY,
                    PROJECT_NUMBER NVARCHAR(50) NULL,
                    PROJECT_HEAD NVARCHAR(250) NULL,
                    PROJECT_STATUS BIT NULL,
                    COMPANY_ID INT NULL
                )
                """);
        for (String schema : List.of(
                "workcube_mikrolink_2025_35",
                "workcube_mikrolink_2026_35",
                "workcube_mikrolink_2026_350")) {
            jdbc.execute("""
                    CREATE TABLE [%s].[ACCOUNT_CARD_ROWS] (
                        ACC_PROJECT_ID INT NULL
                    )
                    """.formatted(schema));
        }

        jdbc.update(
                "INSERT INTO [workcube_mikrolink].[OUR_COMPANY] (COMP_ID) VALUES (35), (350)");
        jdbc.update("""
                INSERT INTO [workcube_mikrolink].[COMPANY]
                    (COMPANY_ID, OUR_COMPANY_ID)
                VALUES (100, 35), (200, 350)
                """);
        jdbc.update("""
                INSERT INTO [workcube_mikrolink].[PRO_PROJECTS]
                    (PROJECT_ID, PROJECT_NUMBER, PROJECT_HEAD, PROJECT_STATUS, COMPANY_ID)
                VALUES
                    (10, N'OWN', N'Canonical owner project', 1, 100),
                    (20, N'EQIL5', N'Equinix accounting project', 1, 200),
                    (30, N'LEAK', N'Other tenant project', 1, 200)
                """);
        jdbc.update("""
                INSERT INTO [workcube_mikrolink_2025_35].[ACCOUNT_CARD_ROWS]
                    (ACC_PROJECT_ID)
                VALUES (20)
                """);
        jdbc.update("""
                INSERT INTO [workcube_mikrolink_2026_35].[ACCOUNT_CARD_ROWS]
                    (ACC_PROJECT_ID)
                VALUES (20), (99), (NULL), (0)
                """);
        jdbc.update("""
                INSERT INTO [workcube_mikrolink_2026_350].[ACCOUNT_CARD_ROWS]
                    (ACC_PROJECT_ID)
                VALUES (30)
                """);

        repository = new ProjectOptionsRepository(jdbc);
    }

    @Test
    void returnsCanonicalAndAccountingReferencedProjectsWithoutTenantPrefixLeak() {
        List<ProjectOptionsRepository.ProjectOption> result =
                repository.findByCompanyId(35L);

        assertThat(result)
                .extracting(ProjectOptionsRepository.ProjectOption::id)
                .containsExactly(10L, 20L, 99L);
        assertThat(result)
                .filteredOn(project -> project.id() == 20L)
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.code()).isEqualTo("EQIL5");
                    assertThat(project.name()).isEqualTo("Equinix accounting project");
                    assertThat(project.companyId()).isEqualTo(35L);
                    assertThat(project.active()).isTrue();
                });
        assertThat(result)
                .filteredOn(project -> project.id() == 99L)
                .singleElement()
                .satisfies(project -> {
                    assertThat(project.code()).isEqualTo("99");
                    assertThat(project.name()).isEqualTo("Katalog kaydı eksik — Proje 99");
                });
        assertThat(result)
                .extracting(ProjectOptionsRepository.ProjectOption::id)
                .doesNotContain(30L);
    }
}
