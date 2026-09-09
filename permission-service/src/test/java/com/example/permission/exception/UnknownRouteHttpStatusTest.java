package com.example.permission.exception;

import com.example.permission.controller.PermissionControllerV1;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.security.ImpersonationContextExtractor;
import com.example.permission.security.SecurityConfig;
import com.example.permission.service.PermissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * gitops#3606 (Codex 01a08828) — pins the HTTP-level outcome through the real
 * SecurityConfig + MVC dispatch, not the handler method: an authenticated call
 * to a path no controller maps is a 404 (it was a 500 with a stack trace), an
 * unauthenticated one is still a 401 from the entry point, and the other MVC
 * client errors carry their own status instead of falling to the 500 catch-all.
 */
@WebMvcTest(controllers = PermissionControllerV1.class)
@Import(SecurityConfig.class)
class UnknownRouteHttpStatusTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private PermissionService permissionService;
    @MockitoBean
    private PermissionRepository permissionRepository;
    @MockitoBean
    private ImpersonationContextExtractor impersonationContextExtractor;
    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void authenticatedUnknownPathIs404NotFound() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/me").with(jwt()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOT_FOUND"));
    }

    @Test
    void unauthenticatedUnknownPathIsStill401FromTheEntryPoint() throws Exception {
        mockMvc.perform(get("/api/v1/permissions/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void wrongMethodIs405WithAllowHeader() throws Exception {
        mockMvc.perform(put("/api/v1/permissions").with(jwt()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string("Allow", containsString("GET")))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"));
    }

    @Test
    void unsupportedRequestContentTypeIs415() throws Exception {
        mockMvc.perform(post("/api/v1/permissions/check").with(jwt())
                        .contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error").value("UNSUPPORTED_MEDIA_TYPE"));
    }
}
