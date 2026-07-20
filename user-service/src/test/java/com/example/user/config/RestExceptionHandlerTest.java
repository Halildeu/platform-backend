package com.example.user.config;

import com.example.user.dto.ApiErrorResponse;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.http.HttpMethod;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice D (#2721) — unit tests for the three 500→4xx handlers added on top of
 * RestExceptionHandler. Direct instantiation keeps these tests fast and does
 * not require a full @SpringBootTest ApplicationContext.
 */
class RestExceptionHandlerTest {

    private final RestExceptionHandler handler = new RestExceptionHandler();

    @Test
    void noResourceFound_returns404WithResourcePath() {
        NoResourceFoundException ex = new NoResourceFoundException(HttpMethod.GET, "api/v1/users/foobar");

        ResponseEntity<ApiErrorResponse> resp = handler.handleNoResourceFound(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        ApiErrorResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(body.message()).contains("api/v1/users/foobar");
    }

    @Test
    void methodArgumentTypeMismatch_returns400WithFieldErrors() throws NoSuchMethodException {
        MethodParameter param = new MethodParameter(
                RestExceptionHandlerTest.class.getDeclaredMethod("dummyLongParam", Long.class), 0);
        MethodArgumentTypeMismatchException ex =
                new MethodArgumentTypeMismatchException("abc", Long.class, "id", param, new IllegalArgumentException("parse fail"));

        ResponseEntity<ApiErrorResponse> resp = handler.handleTypeMismatch(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiErrorResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo("ERR_BAD_REQUEST");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) body.meta().get("fieldErrors");
        assertThat(fieldErrors).hasSize(1);
        Map<String, Object> fe = fieldErrors.get(0);
        assertThat(fe.get("field")).isEqualTo("id");
        assertThat(fe.get("rejectedValue")).isEqualTo("abc");
        assertThat(fe.get("expectedType")).isEqualTo("Long");
    }

    @Test
    void missingServletRequestParameter_returns400WithFieldName() {
        MissingServletRequestParameterException ex =
                new MissingServletRequestParameterException("q", "String");

        ResponseEntity<ApiErrorResponse> resp = handler.handleMissingParameter(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiErrorResponse body = resp.getBody();
        assertThat(body).isNotNull();
        assertThat(body.errorCode()).isEqualTo("ERR_BAD_REQUEST");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fieldErrors = (List<Map<String, Object>>) body.meta().get("fieldErrors");
        assertThat(fieldErrors).hasSize(1);
        Map<String, Object> fe = fieldErrors.get(0);
        assertThat(fe.get("field")).isEqualTo("q");
        assertThat(fe.get("expectedType")).isEqualTo("String");
        assertThat(fe.get("message")).isEqualTo("required");
    }

    // Reflection target for the MethodParameter constructor above.
    @SuppressWarnings("unused")
    private void dummyLongParam(Long id) {}
}
