package com.example.schema.config;

import com.example.schema.catalog.CatalogReader;
import com.example.schema.catalog.CatalogSourceRegistry;
import com.example.schema.controller.SchemaController;
import com.example.schema.model.SchemaSnapshot;
import com.example.schema.service.PathFinderService;
import com.example.schema.service.QuerySuggestionService;
import com.example.schema.service.ReportingContractService;
import com.example.schema.service.SchemaDriftService;
import com.example.schema.service.SchemaExtractService;
import com.example.schema.service.SchemaHealthService;
import com.example.schema.service.SchemaLookupService;
import com.example.schema.service.SchemaSnapshotService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * gitops#3608 — pins the security integration itself, not just the classes:
 * the interceptor is registered on {@code /api/v1/schema/**} through the real
 * {@link SecurityConfig} + {@link SchemaWebMvcConfig}, JWT callers are gated,
 * JWT-less in-cluster callers on the permitAll paths are not.
 */
@WebMvcTest(controllers = SchemaController.class)
@Import({SecurityConfig.class, SchemaWebMvcConfig.class, ReportModuleGateWiringTest.Sources.class})
class ReportModuleGateWiringTest {

    private static final String GOOD = "good-token";

    @Autowired
    MockMvc mvc;

    @MockitoBean
    JwtDecoder jwtDecoder;
    @MockitoBean
    ReportModuleAccessGate gate;

    @MockitoBean
    SchemaExtractService extractService;
    @MockitoBean
    SchemaSnapshotService snapshotService;
    @MockitoBean
    SchemaLookupService lookupService;
    @MockitoBean
    PathFinderService pathFinderService;
    @MockitoBean
    SchemaHealthService healthService;
    @MockitoBean
    SchemaDriftService driftService;
    @MockitoBean
    QuerySuggestionService querySuggestionService;
    @MockitoBean
    ReportingContractService reportingContractService;

    @TestConfiguration
    static class Sources {
        @Bean
        CatalogSourceRegistry catalogSourceRegistry() {
            CatalogReader primary = mock(CatalogReader.class);
            when(primary.sourceId()).thenReturn(CatalogSourceRegistry.PRIMARY_SOURCE_ID);
            when(primary.engine()).thenReturn("mssql");
            when(primary.defaultSchema()).thenReturn("workcube_mikrolink");
            return new CatalogSourceRegistry(List.of(primary));
        }
    }

    @BeforeEach
    void stubs() {
        Instant now = Instant.now();
        when(jwtDecoder.decode(GOOD)).thenReturn(Jwt.withTokenValue(GOOD)
                .header("alg", "none").subject("keycloak-uuid")
                .issuedAt(now).expiresAt(now.plusSeconds(600)).build());
        when(snapshotService.buildSnapshot(any(), anyString())).thenReturn(SchemaSnapshot.builder()
                .version("v1")
                .metadata(new SchemaSnapshot.Metadata("mssql", "host", "db", "schema", now, 0, 0, 0, 0))
                .tables(Map.of())
                .relationships(List.of())
                .domains(Map.of())
                .analysis(new SchemaSnapshot.Analysis(List.of(), List.of()))
                .build());
    }

    @Test
    void jwtCallerWithoutReportModuleGets403FromTheRegisteredInterceptor() throws Exception {
        when(gate.decide(GOOD)).thenReturn(new ReportModuleAccessGate.Decision(false, "no_report_module"));

        mvc.perform(get("/api/v1/schema/sources").header("Authorization", "Bearer " + GOOD))
                .andExpect(status().isForbidden())
                .andExpect(content().string(containsString("\"reason\":\"no_report_module\"")));
    }

    @Test
    void jwtCallerWithReportModulePasses() throws Exception {
        when(gate.decide(GOOD)).thenReturn(new ReportModuleAccessGate.Decision(true, "module_view"));

        mvc.perform(get("/api/v1/schema/sources").header("Authorization", "Bearer " + GOOD))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("workcube")));
        verify(gate).decide(GOOD);
    }

    @Test
    void jwtCallerOnThePermitAllSnapshotPathIsStillGated() throws Exception {
        when(gate.decide(GOOD)).thenReturn(new ReportModuleAccessGate.Decision(false, "authz_unavailable:http_503"));

        mvc.perform(get("/api/v1/schema/snapshot").header("Authorization", "Bearer " + GOOD))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousCallerOnAProtectedPathIs401BeforeTheGate() throws Exception {
        mvc.perform(get("/api/v1/schema/sources"))
                .andExpect(status().isUnauthorized());
        verify(gate, never()).decide(any());
    }

    @Test
    void jwtLessInClusterSnapshotCallKeepsTheControllerKeyGuardAndSkipsTheGate() throws Exception {
        // empty configured internal key = dev/test passthrough (existing contract, SchemaControllerAuthTest)
        mvc.perform(get("/api/v1/schema/snapshot"))
                .andExpect(status().isOk());
        verify(gate, never()).decide(any());
    }
}
