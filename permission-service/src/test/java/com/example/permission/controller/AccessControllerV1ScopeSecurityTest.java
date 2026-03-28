package com.example.permission.controller;

import com.example.permission.security.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.example.permission.service.AccessRoleService;
import com.example.permission.service.UserScopeService;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

@WebMvcTest(controllers = AccessControllerV1.class)
@Import(SecurityConfig.class)
class AccessControllerV1ScopeSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AccessRoleService accessRoleService;

    @MockitoBean
    private UserScopeService userScopeService;

    @Test
    void whenMissingScopeManagePermission_thenScopeCrudIsForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/roles/users/1/scopes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("other-permission")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType": "COMPANY", "scopeRefId": "123"}
                                """))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/roles/users/1/scopes/COMPANY/123")
                        .param("permissionCode", "permission-scope-manage")
                        .with(jwt().authorities(new SimpleGrantedAuthority("something-else"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void whenHasScopeManagePermission_thenScopeCrudSucceeds() throws Exception {
        mockMvc.perform(post("/api/v1/roles/users/1/scopes")
                        .with(jwt().authorities(new SimpleGrantedAuthority("permission-scope-manage")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scopeType": "COMPANY", "scopeRefId": "123"}
                                """))
                .andExpect(status().is2xxSuccessful());

        mockMvc.perform(delete("/api/v1/roles/users/1/scopes/COMPANY/123")
                        .param("permissionCode", "permission-scope-manage")
                        .with(jwt().authorities(new SimpleGrantedAuthority("permission-scope-manage"))))
                .andExpect(status().isNoContent());
    }
}
