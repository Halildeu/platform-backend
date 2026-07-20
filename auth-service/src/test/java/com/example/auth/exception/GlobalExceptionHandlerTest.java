package com.example.auth.exception;

import com.example.auth.dto.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Session 47 stabilization regression — pin the
 * {@link HttpRequestMethodNotSupportedException} branch to
 * {@code 405 METHOD_NOT_ALLOWED} with the {@code Allow} response
 * header (RFC 7231 §6.5.5). Was leaking as 500 {@code INTERNAL_ERROR}
 * via {@code handleGeneric} on live testai 2026-05-12, breaking FE
 * error mapping and audit traces.
 *
 * <p>Reproduction: {@code GET /api/v1/impersonation/sessions?status=ACTIVE}
 * (only {@code @PostMapping} on the root path, {@code @GetMapping("/active")}
 * for lookup) used to return {@code 500 INTERNAL_ERROR}. After this
 * fix, returns {@code 405 METHOD_NOT_ALLOWED} + {@code Allow: POST}
 * header.
 *
 * <p>Codex {@code 019e1dd6} REVISE-1 absorb:
 * <ul>
 *   <li>Direct handler tests pin the response shape + Allow header
 *       branch logic.</li>
 *   <li>MockMvc standalone tests exercise the actual Spring routing
 *       path (dispatcher → handler-mapping → 405 fallback →
 *       {@code @ControllerAdvice} resolution) using a minimal POST-only
 *       fixture controller.</li>
 * </ul>
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new PostOnlyFixtureController())
                .setControllerAdvice(handler)
                .build();
    }

    @Test
    void mapsMethodNotSupportedTo405WithAllowHeader() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().getMessage()).contains("GET");
        assertThat(response.getBody().getMessage()).contains("desteklenmiyor");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW))
                .as("RFC 7231 §6.5.5 MUST: 405 response includes Allow header")
                .isEqualTo("POST");
    }

    @Test
    void mapsMethodNotSupportedWithoutSupportedMethodsTo405() {
        // Edge case: supportedMethods can be null (some Spring paths
        // synthesize the exception without populating it). Should still
        // return 405 with no Allow header rather than NPE'ing.
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getError()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getHeaders().getFirst(HttpHeaders.ALLOW)).isNull();
    }

    @Test
    void mapsNoResourceFoundTo404() {
        // Regression guard: NoResourceFound continues to return 404 NOT_FOUND
        // after the 405 handler addition (no overlap, ordering OK).
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/unknown/path");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().getError()).isEqualTo("NOT_FOUND");
    }

    /**
     * Routing-path regression — actual Spring dispatcher flow (vs
     * direct handler call). Codex {@code 019e1dd6} REVISE-1 ask:
     * "Mevcut 404 testi de gerçek 'ordering guard' değil, çünkü
     * handler metodunu doğrudan çağırıyor."
     *
     * <p>{@link PostOnlyFixtureController} mounts a POST-only endpoint
     * mirroring the {@code ImpersonationController} root path
     * geometry. GET against it goes through Spring's
     * {@code RequestMappingInfoHandlerMapping.handleNoMatch}, raising
     * {@link HttpRequestMethodNotSupportedException}, which the
     * {@code @ControllerAdvice} resolves to 405.
     */
    @Test
    void routingDispatcherProduces405WithAllowOnGetToPostOnly() throws Exception {
        mockMvc.perform(get("/fixture/post-only"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().string(HttpHeaders.ALLOW, "POST"))
                .andExpect(jsonPath("$.error").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("GET")));
    }

    @Test
    void routingDispatcherProduces200OnPostToPostOnly() throws Exception {
        mockMvc.perform(post("/fixture/post-only")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // #2555 Slice-D-parity — malformed JSON body → 400 BAD_REQUEST.
    @Test
    void handleUnreadable_returns400() {
        org.springframework.http.converter.HttpMessageNotReadableException ex =
                new org.springframework.http.converter.HttpMessageNotReadableException("truncated");
        ResponseEntity<ErrorResponse> resp = handler.handleUnreadable(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getError()).isEqualTo("BAD_REQUEST");
    }

    // #2555 Slice-D-parity — path variable type mismatch → 400 + fieldErrors.
    @Test
    void handleTypeMismatch_returns400WithFieldName() throws NoSuchMethodException {
        org.springframework.core.MethodParameter param = new org.springframework.core.MethodParameter(
                GlobalExceptionHandlerTest.class.getDeclaredMethod("dummyLongParam", Long.class), 0);
        org.springframework.web.method.annotation.MethodArgumentTypeMismatchException ex =
                new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                        "abc", Long.class, "id", param, new IllegalArgumentException("parse fail"));
        ResponseEntity<ErrorResponse> resp = handler.handleTypeMismatch(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getError()).isEqualTo("BAD_REQUEST");
        assertThat(resp.getBody().getFieldErrors()).hasSize(1);
        assertThat(resp.getBody().getFieldErrors().get(0).getField()).isEqualTo("id");
    }

    // #2555 Slice-D-parity — missing required parameter → 400 + fieldErrors.
    @Test
    void handleMissingParameter_returns400WithFieldName() {
        org.springframework.web.bind.MissingServletRequestParameterException ex =
                new org.springframework.web.bind.MissingServletRequestParameterException("q", "String");
        ResponseEntity<ErrorResponse> resp = handler.handleMissingParameter(ex);
        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getError()).isEqualTo("BAD_REQUEST");
        assertThat(resp.getBody().getFieldErrors()).hasSize(1);
        assertThat(resp.getBody().getFieldErrors().get(0).getField()).isEqualTo("q");
    }

    @SuppressWarnings("unused")
    private void dummyLongParam(Long id) {}

    @RestController
    @RequestMapping("/fixture/post-only")
    static class PostOnlyFixtureController {

        /**
         * Mirrors {@code ImpersonationController} geometry: only POST
         * mapped on the root path. GET should produce 405 (handled by
         * {@link GlobalExceptionHandler}) — not 404, not 500.
         */
        @PostMapping
        public String create() {
            return "{\"ok\":true}";
        }
    }
}
