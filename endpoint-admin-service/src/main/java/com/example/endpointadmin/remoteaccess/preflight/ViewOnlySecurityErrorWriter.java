package com.example.endpointadmin.remoteaccess.preflight;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/** Strict, bounded error body for failures that occur before the controller. */
public final class ViewOnlySecurityErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {
    private final ObjectMapper mapper;

    public ViewOnlySecurityErrorWriter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public void commence(HttpServletRequest request,
                         HttpServletResponse response,
                         AuthenticationException authenticationException) throws IOException {
        write(response, 401, "OIDC_INVALID", "GitHub OIDC authentication failed closed");
    }

    @Override
    public void handle(HttpServletRequest request,
                       HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException, ServletException {
        write(response, 403, "OIDC_CLAIM_MISMATCH", "GitHub OIDC authority is not permitted");
    }

    private void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.resetBuffer();
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        mapper.writeValue(response.getOutputStream(), new ViewOnlyAuthorityErrorResponse(
                "faz22.6.viewOnlyPreflightError.v1", UUID.randomUUID(), code, message,
                false, 0, false));
        response.flushBuffer();
    }
}
