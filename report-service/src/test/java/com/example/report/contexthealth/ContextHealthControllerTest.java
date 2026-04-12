package com.example.report.contexthealth;

import com.example.report.contexthealth.dto.ContextHealthGridMetaDto;
import com.example.report.contexthealth.dto.ContextHealthGridMetaDto.ColumnDef;
import com.example.report.dto.ChartResultDto;
import com.example.report.dto.KpiResultDto;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for {@link ContextHealthController}.
 * Uses @WebMvcTest for slice testing with mocked service dependencies.
 * "local" profile activates permitAll security config.
 */
@WebMvcTest(ContextHealthController.class)
@TestPropertySource(properties = {
        "context-health.enabled=true",
        "context-health.data-dir=/tmp/test-data",
        "context-health.index-dir=/tmp/test-index",
        "context-health.cache-ttl-seconds=30"
})
@ActiveProfiles("local")
@Import({ContextHealthConfig.class, com.example.report.config.SecurityConfigLocal.class})
class ContextHealthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContextHealthKpiService kpiService;

    @MockitoBean
    private ContextHealthChartService chartService;

    @MockitoBean
    private ContextHealthGridService gridService;

    @MockitoBean
    private ContextHealthSessionService sessionService;

    @MockitoBean
    private ContextHealthFileReader fileReader;

    @MockitoBean
    private CacheManager cacheManager;

    // ---- /status endpoint ----

    @Test
    void getStatus_configured_returnsOk() throws Exception {
        when(fileReader.isConfigured()).thenReturn(true);
        when(fileReader.countDataFiles()).thenReturn(5);
        when(fileReader.readSystemStatus()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/context-health/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.fileCount").value(5))
                .andExpect(jsonPath("$.overallStatus").value("OK"))
                .andExpect(jsonPath("$.lastRefresh").isNotEmpty());
    }

    @Test
    void getStatus_notConfigured_returnsNotConfigured() throws Exception {
        when(fileReader.isConfigured()).thenReturn(false);
        when(fileReader.countDataFiles()).thenReturn(0);
        when(fileReader.readSystemStatus()).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/context-health/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("NOT_CONFIGURED"));
    }

    @Test
    void getStatus_withSystemStatusOverride_usesOverallFromStatus() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var statusNode = mapper.readTree("{\"overall_status\": \"WARN\"}");

        when(fileReader.isConfigured()).thenReturn(true);
        when(fileReader.countDataFiles()).thenReturn(3);
        when(fileReader.readSystemStatus()).thenReturn(java.util.Optional.of(statusNode));

        mockMvc.perform(get("/api/v1/context-health/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overallStatus").value("WARN"));
    }

    // ---- /kpis endpoint ----

    @Test
    void getKpis_returnsKpiList() throws Exception {
        List<KpiResultDto> kpis = List.of(
                new KpiResultDto("health-score", "Health Score", "number", 85, "85/100", null, "success", null, null),
                new KpiResultDto("drift-status", "Drift", "text", "Clean", "Clean", null, "success", null, null)
        );
        when(kpiService.computeKpis()).thenReturn(kpis);

        mockMvc.perform(get("/api/v1/context-health/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].id").value("health-score"))
                .andExpect(jsonPath("$[0].tone").value("success"))
                .andExpect(jsonPath("$[1].id").value("drift-status"));
    }

    @Test
    void getKpis_empty_returnsEmptyArray() throws Exception {
        when(kpiService.computeKpis()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/context-health/kpis"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- /charts endpoint ----

    @Test
    void getCharts_returnsChartList() throws Exception {
        List<ChartResultDto> charts = List.of(
                new ChartResultDto("test-chart", "Test Chart", "bar", "md",
                        List.of(Map.of("label", "A", "value", 10)),
                        Map.of("showValues", true), null)
        );
        when(chartService.computeCharts()).thenReturn(charts);

        mockMvc.perform(get("/api/v1/context-health/charts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].id").value("test-chart"))
                .andExpect(jsonPath("$[0].chartType").value("bar"))
                .andExpect(jsonPath("$[0].data[0].label").value("A"));
    }

    // ---- /grids endpoint ----

    @Test
    void getGrids_returnsGridMetaList() throws Exception {
        List<ContextHealthGridMetaDto> grids = List.of(
                new ContextHealthGridMetaDto("active-projects", "Active Projects", List.of(
                        new ColumnDef("projectId", "Project ID", "text", 200)
                ))
        );
        when(gridService.listGrids()).thenReturn(grids);

        mockMvc.perform(get("/api/v1/context-health/grids"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].gridId").value("active-projects"))
                .andExpect(jsonPath("$[0].columns[0].field").value("projectId"));
    }

    // ---- /grids/{gridId} endpoint ----

    @Test
    void getGridData_returnsRows() throws Exception {
        List<Map<String, Object>> rows = List.of(
                Map.of("projectId", "PRJ-001", "title", "Demo Project")
        );
        when(gridService.getGridData("active-projects")).thenReturn(rows);

        mockMvc.perform(get("/api/v1/context-health/grids/active-projects"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].projectId").value("PRJ-001"));
    }

    @Test
    void getGridData_unknownGrid_returnsEmptyList() throws Exception {
        when(gridService.getGridData("unknown")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/context-health/grids/unknown"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // ---- /session endpoint ----

    @Test
    void getSession_returnsSessionData() throws Exception {
        Map<String, Object> sessionData = Map.of(
                "available", true,
                "overallStatus", "OK",
                "generatedAt", "2025-01-01T00:00:00Z"
        );
        when(sessionService.getSessionData()).thenReturn(sessionData);

        mockMvc.perform(get("/api/v1/context-health/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.overallStatus").value("OK"));
    }

    @Test
    void getSession_unavailable_returnsAvailableFalse() throws Exception {
        when(sessionService.getSessionData()).thenReturn(Map.of("available", false));

        mockMvc.perform(get("/api/v1/context-health/session"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false));
    }

    // ---- /refresh endpoint ----

    @Test
    void refresh_returns204AndEvictsCaches() throws Exception {
        var cache = mock(org.springframework.cache.Cache.class);
        when(cacheManager.getCache(anyString())).thenReturn(cache);

        mockMvc.perform(post("/api/v1/context-health/refresh"))
                .andExpect(status().isNoContent());

        verify(cacheManager).getCache("contextHealthFiles");
        verify(cacheManager).getCache("contextHealthKpis");
        verify(cacheManager).getCache("contextHealthCharts");
        verify(cacheManager).getCache("contextHealthGrids");
        verify(cache, times(4)).clear();
    }

    @Test
    void refresh_nullCache_doesNotThrow() throws Exception {
        when(cacheManager.getCache(anyString())).thenReturn(null);

        mockMvc.perform(post("/api/v1/context-health/refresh"))
                .andExpect(status().isNoContent());
    }

    // Service error propagation: Controller has no @ExceptionHandler,
    // so Spring MVC wraps RuntimeException as ServletException.
    // Verifying via assertThrows instead of status check.

    @Test
    void getKpis_serviceThrows_propagatesException() {
        when(kpiService.computeKpis()).thenThrow(new RuntimeException("Service error"));

        assertThrows(Exception.class, () ->
                mockMvc.perform(get("/api/v1/context-health/kpis")));
    }

    @Test
    void getCharts_serviceThrows_propagatesException() {
        when(chartService.computeCharts()).thenThrow(new RuntimeException("Service error"));

        assertThrows(Exception.class, () ->
                mockMvc.perform(get("/api/v1/context-health/charts")));
    }

    @Test
    void getGridData_serviceThrows_propagatesException() {
        when(gridService.getGridData("active-projects")).thenThrow(new RuntimeException("Service error"));

        assertThrows(Exception.class, () ->
                mockMvc.perform(get("/api/v1/context-health/grids/active-projects")));
    }
}
