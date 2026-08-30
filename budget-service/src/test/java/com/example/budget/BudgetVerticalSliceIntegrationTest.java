package com.example.budget;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.budget.security.BudgetAuthorizationClient;
import com.example.budget.security.BudgetAuthorizationClient.AuthorizationSnapshot;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(BudgetVerticalSliceIntegrationTest.TestJwtConfiguration.class)
class BudgetVerticalSliceIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void createEditSubmitApproveControlAndTenantGuardsFormOnePersistentJourney() throws Exception {
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/budgets")
                        .with(actor("editor-1", "tenant-a", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":35,"fiscalYear":2026,"baseCurrency":"TRY"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString());
        String planId = created.get("planId").asText();
        String versionId = created.get("versionId").asText();

        mvc.perform(put("/api/v1/budgets/{planId}/versions/{versionId}/lines", planId, versionId)
                        .with(actor("editor-1", "tenant-a", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[
                                  {"period":"2026-01","accountCode":"740.01","costCenterCode":"CC-35",
                                   "direction":"EXPENSE","plannedAmount":1000.00,"currency":"TRY",
                                   "description":"Sentetik TEST bütçe satırı"}
                                ]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[0].plannedAmount").value(1000.0));

        mvc.perform(post("/api/v1/budgets/{planId}/versions/{versionId}/submit", planId, versionId)
                        .with(actor("editor-1", "tenant-a", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUBMITTED"));

        mvc.perform(post("/api/v1/budgets/{planId}/versions/{versionId}/approve", planId, versionId)
                        .with(actor("editor-1", "tenant-a", 35, "SCOPE_budget:approve"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/v1/budgets/{planId}/versions/{versionId}/approve", planId, versionId)
                        .with(actor("approver-2", "tenant-a", 35, "SCOPE_budget:approve"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedBy").value("approver-2"));

        mvc.perform(put("/api/v1/budgets/{planId}/versions/{versionId}/lines", planId, versionId)
                        .with(actor("editor-1", "tenant-a", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[
                                  {"period":"2026-02","accountCode":"740.01","direction":"EXPENSE",
                                   "plannedAmount":1.00,"currency":"TRY"}
                                ]}
                                """))
                .andExpect(status().isConflict());

        jdbc.update("""
                INSERT INTO actual_snapshots (
                  id, tenant_id, company_id, fiscal_year, period_start, journal_card_id,
                  journal_row_id, action_type, action_id, resolution_status, direction,
                  normalized_amount, currency, source_hash, is_cancelled, synced_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), "tenant-a", 35L, 2026,
                java.sql.Date.valueOf("2026-01-01"), 9001L, 9101L, 121, 7001L,
                "HEADER_ONLY", "EXPENSE", 500.00, "TRY", "a".repeat(64), false,
                OffsetDateTime.now(ZoneOffset.UTC));

        mvc.perform(get("/api/v1/budgets/{planId}/versions/{versionId}/control", planId, versionId)
                        .with(actor("reader-1", "tenant-a", 35, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value(1000.0))
                .andExpect(jsonPath("$.accountingActual").value(500.0))
                .andExpect(jsonPath("$.allocatedActual").value(0))
                .andExpect(jsonPath("$.unallocatedActual").value(500.0))
                .andExpect(jsonPath("$.unresolvedActual").value(500.0))
                .andExpect(jsonPath("$.remaining").value(500.0))
                .andExpect(jsonPath("$.forecastStatus").value("NOT_LOADED"))
                .andExpect(jsonPath("$.eac").value(nullValue()));

        // gitops#3496 slice C — discovery read: the latest version resolves
        // without ids, per company + fiscal year, tenant-scoped like the rest.
        mvc.perform(get("/api/v1/budgets/plans/current")
                        .with(actor("reader-1", "tenant-a", 35, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35")
                        .param("fiscalYear", "2026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planId").value(planId))
                .andExpect(jsonPath("$.versionId").value(versionId))
                .andExpect(jsonPath("$.lines.length()").value(1));

        mvc.perform(get("/api/v1/budgets/plans/current")
                        .with(actor("reader-1", "tenant-a", 35, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35")
                        .param("fiscalYear", "2031"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/budgets/plans/current")
                        .with(actor("other-tenant", "tenant-b", 35, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35")
                        .param("fiscalYear", "2026"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/budgets/{planId}/versions/{versionId}", planId, versionId)
                        .with(actor("other-tenant", "tenant-b", 35, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/budgets/{planId}/versions/{versionId}", planId, versionId)
                        .with(actor("wrong-company", "tenant-a", 36, "SCOPE_budget:read"))
                        .header("X-Company-Id", "35"))
                .andExpect(status().isForbidden());
    }

    @Test
    void mixedCurrencyBudgetLineFailsClosedUntilFxPlanningExists() throws Exception {
        JsonNode created = mapper.readTree(mvc.perform(post("/api/v1/budgets")
                        .with(actor("editor-fx", "tenant-fx", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"companyId":35,"fiscalYear":2027,"baseCurrency":"TRY"}
                                """))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        mvc.perform(put("/api/v1/budgets/{planId}/versions/{versionId}/lines",
                        created.get("planId").asText(), created.get("versionId").asText())
                        .with(actor("editor-fx", "tenant-fx", 35, "SCOPE_budget:write"))
                        .header("X-Company-Id", "35")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"lines":[
                                  {"period":"2027-01","accountCode":"740.01","direction":"EXPENSE",
                                   "plannedAmount":100.00,"currency":"USD"}
                                ]}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void actionType121IsVersionedAsExpensePlanNotBank() {
        jdbc.queryForObject("""
                SELECT source_family FROM source_type_registry
                 WHERE action_type=121 AND registry_version=1
                """, String.class);
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject("""
                SELECT source_family FROM source_type_registry
                 WHERE action_type=121 AND registry_version=1
                """, String.class)).isEqualTo("EXPENSE_PLAN");
    }

    private RequestPostProcessor actor(String subject, String tenant, long company, String authority) {
        return jwt().jwt(token -> token
                        .tokenValue(subject + "|" + company)
                        .subject(subject)
                        .claim("tenant_id", tenant)
                        .claim("company_ids", List.of(company)))
                .authorities(new SimpleGrantedAuthority(authority));
    }

    @TestConfiguration
    static class TestJwtConfiguration {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() {
            return token -> Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .subject("test")
                    .claim("tenant_id", "tenant-a")
                    .claim("company_ids", List.of(35))
                    .build();
        }

        @Bean
        @Primary
        BudgetAuthorizationClient testBudgetAuthorizationClient() {
            return token -> {
                String[] parts = token.split("\\|", 2);
                return new AuthorizationSnapshot(
                        parts[0],
                        Set.of(Long.parseLong(parts[1])),
                        Set.of(),
                        false);
            };
        }
    }
}
