package com.example.permission.exception;

import com.example.permission.dto.ErrorResponse;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Slice E (#2555) — Spring 6.1+ cascade validation (@Valid on @RequestParam
 * or @PathVariable) fires HandlerMethodValidationException, not
 * MethodArgumentNotValidException. Locks the 400 + VALIDATION_ERROR
 * contract so future refactors cannot silently regress the client-error
 * semantics of parameter cascade violations.
 */
class GlobalExceptionHandlerSliceETest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void handleHandlerMethodValidation_returns400_emptyResults() throws Exception {
        HandlerMethodValidationException ex = new HandlerMethodValidationException(
                new EmptyValidationResult());

        ResponseEntity<ErrorResponse> resp = handler.handleHandlerMethodValidation(ex);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(resp.getBody()).isNotNull();
        assertThat(resp.getBody().getError()).isEqualTo("VALIDATION_ERROR");
    }

    private static final class EmptyValidationResult implements MethodValidationResult {
        @Override
        public Object getTarget() { return new Object(); }

        @Override
        public Method getMethod() {
            try {
                return GlobalExceptionHandlerSliceETest.class.getDeclaredMethod("dummyMethod");
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public boolean isForReturnValue() { return false; }

        @Override
        public List<ParameterValidationResult> getParameterValidationResults() {
            return Collections.emptyList();
        }

        @Override
        public List<org.springframework.context.MessageSourceResolvable> getCrossParameterValidationResults() {
            return Collections.emptyList();
        }
    }

    @SuppressWarnings("unused")
    private static String dummyMethod() { return "x"; }
}
