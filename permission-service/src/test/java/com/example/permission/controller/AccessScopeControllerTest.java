package com.example.permission.controller;

import com.example.permission.dataaccess.AccessScopeException;
import com.example.permission.dataaccess.AccessScopeService;
import com.example.permission.dataaccess.DataAccessScope;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AccessScopeController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(AccessScopeExceptionHandler.class)
@WithMockUser(roles = "ADMIN")
class AccessScopeControllerTest {

    private static final UUID USER = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessScopeService accessScopeService;

    @Test
    void postGrant_201_validRequest_returnsScopeAndOpenFgaCoordinates() throws Exception {
        when(accessScopeService.grant(eq(USER), eq(1L),
                eq(DataAccessScope.ScopeKind.COMPANY), eq("[\"1001\"]"), any()))
                .thenReturn(scope(42L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]"));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"1001\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scopeId").value(42))
                .andExpect(jsonPath("$.scopeKind").value("COMPANY"))
                .andExpect(jsonPath("$.openFgaObjectType").value("company"))
                .andExpect(jsonPath("$.openFgaObjectId").value("wc-company-1001"));
    }

    @Test
    void postGrant_400_blankScopeRef() throws Exception {
        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": ""
                                }
                                """.formatted(USER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postGrant_409_alreadyGranted() throws Exception {
        when(accessScopeService.grant(any(), any(), any(), any(), any()))
                .thenThrow(new AccessScopeException.ScopeAlreadyGrantedException(
                        "Active scope already exists", new RuntimeException("uq_scope_active_assignment")));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"1001\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ScopeAlreadyGranted"));
    }

    @Test
    void postGrant_422_lineageViolation() throws Exception {
        when(accessScopeService.grant(any(), any(), any(), any(), any()))
                .thenThrow(new AccessScopeException.ScopeValidationException(
                        "Scope reference rejected by data_access lineage guard: invalid scope_ref",
                        new RuntimeException()));

        mockMvc.perform(post("/api/v1/access/scope")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "userId": "%s",
                                  "orgId": 1,
                                  "scopeKind": "COMPANY",
                                  "scopeRef": "[\\"99999\\"]"
                                }
                                """.formatted(USER)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("ScopeValidation"));
    }

    @Test
    void deleteRevoke_204_validId() throws Exception {
        when(accessScopeService.revoke(eq(7L), any()))
                .thenReturn(scope(7L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]"));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 7L))
                .andExpect(status().isNoContent());

        verify(accessScopeService).revoke(eq(7L), any());
    }

    @Test
    void deleteRevoke_404_notFound() throws Exception {
        when(accessScopeService.revoke(eq(999L), any()))
                .thenThrow(new AccessScopeException.ScopeNotFoundException(999L));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 999L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("ScopeNotFound"))
                .andExpect(jsonPath("$.scope_id").value(999));
    }

    @Test
    void deleteRevoke_409_alreadyRevoked() throws Exception {
        when(accessScopeService.revoke(eq(8L), any()))
                .thenThrow(new AccessScopeException.ScopeAlreadyRevokedException(
                        8L, Instant.parse("2026-04-26T10:00:00Z")));

        mockMvc.perform(delete("/api/v1/access/scope/{id}", 8L))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("ScopeAlreadyRevoked"))
                .andExpect(jsonPath("$.scope_id").value(8));
    }

    @Test
    void getList_200_returnsActiveScopesAsListItems() throws Exception {
        when(accessScopeService.listActiveScopes(USER, 1L))
                .thenReturn(List.of(
                        scope(1L, DataAccessScope.ScopeKind.COMPANY, "[\"1001\"]"),
                        scope(2L, DataAccessScope.ScopeKind.PROJECT, "[\"1204\"]")
                ));

        mockMvc.perform(get("/api/v1/access/scope")
                        .param("userId", USER.toString())
                        .param("orgId", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].scopeKind").value("COMPANY"))
                .andExpect(jsonPath("$[1].scopeKind").value("PROJECT"))
                .andExpect(jsonPath("$[0].active").value(true));
    }

    private static DataAccessScope scope(Long id, DataAccessScope.ScopeKind kind, String scopeRef) {
        var s = new DataAccessScope();
        s.setId(id);
        s.setUserId(USER);
        s.setOrgId(1L);
        s.setScopeKind(kind);
        s.setScopeSourceSchema("workcube_mikrolink");
        s.setScopeSourceTable(switch (kind) {
            case COMPANY -> "COMPANY";
            case PROJECT -> "PRO_PROJECTS";
            case BRANCH -> "BRANCH";
            case DEPOT -> "DEPARTMENT";
        });
        s.setScopeRef(scopeRef);
        s.setGrantedAt(Instant.parse("2026-04-25T10:00:00Z"));
        return s;
    }
}
