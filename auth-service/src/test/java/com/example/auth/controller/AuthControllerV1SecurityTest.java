package com.example.auth.controller;

import com.example.auth.dto.JwtResponse;
import com.example.auth.exception.GlobalExceptionHandler;
import com.example.auth.security.SecurityConfig;
import com.example.auth.security.JwtAuthFilter;
import com.example.auth.security.JwtTokenProvider;
import com.example.auth.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuthControllerV1.class)
@AutoConfigureMockMvc
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
@ActiveProfiles("local")
class AuthControllerV1SecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private UserDetailsService userDetailsService;

    @MockitoBean
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void createSession_isPublicForV1AuthEndpoint() throws Exception {
        when(authService.login("admin@example.com", "admin1234", 1L)).thenReturn(
                new JwtResponse(
                        "jwt-token",
                        "admin@example.com",
                        "ADMIN",
                        Set.of("audit-read"),
                        1762624688000L,
                        30
                )
        );

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "admin@example.com",
                                "password", "admin1234",
                                "companyId", 1
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.email").value("admin@example.com"));
    }
}
