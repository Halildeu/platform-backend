package com.example.auth.controller;

import com.example.auth.dto.ForgotPasswordRequest;
import com.example.auth.dto.MessageResponse;
import com.example.auth.dto.RegisterRequest;
import com.example.auth.dto.ResetPasswordRequest;
import com.example.auth.dto.JwtResponse;
import com.example.auth.exception.GlobalExceptionHandler;
import com.example.auth.service.AuthService;
import com.example.auth.service.AuthService.RegistrationResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(
        controllers = AuthControllerV1.class,
        excludeAutoConfiguration = {
                org.springframework.boot.autoconfigure.security.servlet.SecurityAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.SecurityFilterAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration.class,
                org.springframework.boot.autoconfigure.security.oauth2.resource.servlet.OAuth2ResourceServerAutoConfiguration.class
        }
)
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
@ActiveProfiles("local")
class AuthControllerV1Test {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private AuthService authService;

    @MockBean
    private com.example.auth.security.JwtTokenProvider jwtTokenProvider;

    @MockBean
    private org.springframework.security.core.userdetails.UserDetailsService userDetailsService;

    @Test
    void createSession_returnsLoginResponseDto() throws Exception {
        JwtResponse jwt = new JwtResponse(
                "jwt-token",
                "user@example.com",
                "ADMIN",
                Set.of("VIEW_USERS"),
                1762624688000L,
                30
        );
        when(authService.login(eq("user@example.com"), eq("secret"), eq(42L))).thenReturn(jwt);

        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "user@example.com",
                                "password", "secret",
                                "companyId", 42
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").value("jwt-token"))
                .andExpect(jsonPath("$.email").value("user@example.com"))
                .andExpect(jsonPath("$.permissions[0]").value("VIEW_USERS"))
                .andExpect(jsonPath("$.sessionTimeoutMinutes").value(30));

        verify(authService).login("user@example.com", "secret", 42L);
    }

    @Test
    void register_returnsRegistrationResponse() throws Exception {
        RegistrationResult result = new RegistrationResult(100L, "new@example.com", "PENDING_VERIFICATION", "ok");
        when(authService.registerDetailed(any(RegisterRequest.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/auth/registrations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "Yeni Kullanıcı",
                                "email", "new@example.com",
                                "password", "Strong#123"
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(100))
                .andExpect(jsonPath("$.status").value("PENDING_VERIFICATION"))
                .andExpect(jsonPath("$.email").value("new@example.com"));

        ArgumentCaptor<RegisterRequest> captor = ArgumentCaptor.forClass(RegisterRequest.class);
        verify(authService).registerDetailed(captor.capture());
        RegisterRequest legacy = captor.getValue();
        assertThat(legacy.getName()).isEqualTo("Yeni Kullanıcı");
        assertThat(legacy.getEmail()).isEqualTo("new@example.com");
        assertThat(legacy.getPassword()).isEqualTo("Strong#123");
    }

    @Test
    void forgotPassword_returnsMessageDto() throws Exception {
        when(authService.forgotPassword(any(ForgotPasswordRequest.class)))
                .thenReturn(new MessageResponse("Reset maili gönderildi"));

        mockMvc.perform(post("/api/v1/auth/password-resets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("email", "forgot@example.com"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Reset maili gönderildi"));

        ArgumentCaptor<ForgotPasswordRequest> captor = ArgumentCaptor.forClass(ForgotPasswordRequest.class);
        verify(authService).forgotPassword(captor.capture());
        assertThat(captor.getValue().getEmail()).isEqualTo("forgot@example.com");
    }

    @Test
    void resetPassword_passesTokenAndPayload() throws Exception {
        when(authService.resetPassword(any(ResetPasswordRequest.class)))
                .thenReturn(new MessageResponse("Şifre güncellendi"));

        mockMvc.perform(post("/api/v1/auth/password-resets/token-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "token", "token-123",
                                "newPassword", "YeniParola#123"
                        ))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Şifre güncellendi"));

        ArgumentCaptor<ResetPasswordRequest> captor = ArgumentCaptor.forClass(ResetPasswordRequest.class);
        verify(authService).resetPassword(captor.capture());
        ResetPasswordRequest legacy = captor.getValue();
        assertThat(legacy.getToken()).isEqualTo("token-123");
        assertThat(legacy.getNewPassword()).isEqualTo("YeniParola#123");
    }

    @Test
    void verifyEmail_returnsMessageDto() throws Exception {
        when(authService.verifyEmail("token-abc")).thenReturn(new MessageResponse("Doğrulandı"));

        mockMvc.perform(post("/api/v1/auth/email-verifications/token-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Doğrulandı"));

        verify(authService).verifyEmail("token-abc");
    }

    @Test
    void createSession_validationError_returnsErrorResponse() throws Exception {
        mockMvc.perform(post("/api/v1/auth/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "email", "not-an-email",
                                "password", ""
                        ))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.fieldErrors").isArray())
                .andExpect(jsonPath("$.meta.traceId").isNotEmpty());
    }
}
