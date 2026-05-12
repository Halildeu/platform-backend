package com.example.auth.exception;

import com.example.auth.dto.ErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Session 47 stabilization regression — pin the
 * {@link HttpRequestMethodNotSupportedException} branch to
 * {@code 405 METHOD_NOT_ALLOWED} (was leaking as 500
 * {@code INTERNAL_ERROR} via {@code handleGeneric}, which confused FE
 * error mapping and audit traces on live testai).
 *
 * <p>Reproduction: {@code GET /api/v1/impersonation/sessions?status=ACTIVE}
 * (no {@code @GetMapping} on the root path — only {@code @PostMapping}
 * for start, {@code @GetMapping("/active")} for lookup) used to return
 * {@code 500 INTERNAL_ERROR}. After this fix, it returns {@code 405
 * METHOD_NOT_ALLOWED} with {@code METHOD_NOT_ALLOWED} error code.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void mapsMethodNotSupportedTo405() {
        HttpRequestMethodNotSupportedException ex =
                new HttpRequestMethodNotSupportedException("GET");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotAllowed(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().message()).contains("GET");
        assertThat(response.getBody().message()).contains("desteklenmiyor");
    }

    @Test
    void mapsNoResourceFoundTo404() {
        // Regression guard: NoResourceFound continues to return 404 NOT_FOUND
        // after the 405 handler addition (no overlap, ordering OK).
        NoResourceFoundException ex = new NoResourceFoundException(
                org.springframework.http.HttpMethod.GET, "/unknown/path");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().error()).isEqualTo("NOT_FOUND");
    }
}
