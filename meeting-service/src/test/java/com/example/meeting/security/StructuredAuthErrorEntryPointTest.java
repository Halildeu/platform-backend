package com.example.meeting.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.AuthenticationException;

class StructuredAuthErrorEntryPointTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final StructuredAuthErrorEntryPoint sut = new StructuredAuthErrorEntryPoint(objectMapper);

    @Test
    void emits_stableTokenMissingCode_whenNoAuthorizationHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.commence(request, response, tokenError("invalid_token"));

        JsonNode body = read(response);
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_JSON_VALUE);
        assertThat(body.get("errorCode").asText()).isEqualTo("AUTH_TOKEN_MISSING");
        assertThat(body.get("retryable").asBoolean()).isFalse();
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("correlationId").asText()).isNotBlank();
    }

    @Test
    void emits_tokenInvalidCode_whenAuthorizationHeaderPresent() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer opaque-token-redacted");
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.commence(request, response, tokenError("invalid_token"));

        JsonNode body = read(response);
        assertThat(body.get("errorCode").asText()).isEqualTo("AUTH_TOKEN_INVALID");
    }

    @Test
    void body_neverContainsTokenOrClaimValues() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        // A well-formed but expired bearer token; the entry point MUST NOT
        // reflect its value back into the response body.
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer eyJhbGciOiJIUzI1NiJ9.mock-payload.mock-signature");
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.commence(request, response, tokenError("invalid_token"));

        assertThat(response.getContentAsString())
                .doesNotContain("Bearer")
                .doesNotContain("eyJhbGciOiJIUzI1NiJ9")
                .doesNotContain("mock-signature");
    }

    @Test
    void handle_emitsForbiddenBody_whenAuthenticatedButUnauthorized() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.handle(request, response, new AccessDeniedException("Access is denied"));

        assertThat(response.getStatus()).isEqualTo(403);
        JsonNode body = read(response);
        assertThat(body.get("errorCode").asText()).isEqualTo("AUTH_FORBIDDEN");
        assertThat(body.get("retryable").asBoolean()).isFalse();
    }

    @Test
    void echoes_correlationIdHeaderVerbatim_whenClientSuppliesOne() throws Exception {
        String suppliedCorrelationId = "abcd-1234-ef56";
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        request.addHeader("X-Correlation-Id", suppliedCorrelationId);
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.commence(request, response, tokenError("invalid_token"));

        JsonNode body = read(response);
        assertThat(body.get("correlationId").asText()).isEqualTo(suppliedCorrelationId);
        assertThat(response.getHeader("X-Correlation-Id")).isEqualTo(suppliedCorrelationId);
    }

    @Test
    void rejects_controlCharactersInSuppliedCorrelationId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/internal/meetings/x/analysis-results");
        // A client injecting \r\n could split the response envelope; the entry
        // point falls back to a fresh UUID instead of echoing the header.
        request.addHeader("X-Correlation-Id", "abc\r\nX-Injected: yes");
        MockHttpServletResponse response = new MockHttpServletResponse();

        sut.commence(request, response, tokenError("invalid_token"));

        JsonNode body = read(response);
        String correlationId = body.get("correlationId").asText();
        assertThat(correlationId).doesNotContain("\r").doesNotContain("\n").doesNotContain("X-Injected");
    }

    private JsonNode read(HttpServletResponse response) throws Exception {
        MockHttpServletResponse mock = (MockHttpServletResponse) response;
        return objectMapper.readTree(mock.getContentAsString());
    }

    private static AuthenticationException tokenError(String code) {
        return new InsufficientAuthenticationException(code);
    }

    // Unused, kept so tests compile even if the interface adds extra defaults.
    @SuppressWarnings("unused")
    private HttpServletRequest ignored;
}
