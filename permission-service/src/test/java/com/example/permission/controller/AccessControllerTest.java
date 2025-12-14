package com.example.permission.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

import com.example.permission.dto.access.AccessModulePolicyDto;
import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.service.AccessRoleService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

@WebMvcTest(controllers = AccessController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "ADMIN")
class AccessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AccessRoleService accessRoleService;

    @Test
    void listRoles_returnsOk() throws Exception {
        AccessRoleDto role = new AccessRoleDto(1L, "USER_MANAGER", "", 0, false, "2025-01-01T00:00:00Z", "system",
                List.of(new AccessModulePolicyDto("USER_MANAGEMENT", "Kullanıcı Yönetimi", "MANAGE", "2025-01-01T00:00:00Z", "system")));
        when(accessRoleService.listRoles()).thenReturn(List.of(role));

        mockMvc.perform(get("/api/access/roles").accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
    }
}
