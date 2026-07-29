package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Exact error schema for failures raised after GitHub OIDC authentication. */
@RestControllerAdvice(assignableTypes = ViewOnlyPreflightController.class)
@Profile("!local & !dev")
@ConditionalOnProperty(prefix = "endpoint-admin.view-only-authority", name = "enabled", havingValue = "true")
public final class ViewOnlyPreflightExceptionHandler {
    private final ViewOnlyAuthorityHttpErrorMapper mapper = new ViewOnlyAuthorityHttpErrorMapper();

    @ExceptionHandler(ViewOnlyAuthorityException.class)
    public ResponseEntity<ViewOnlyAuthorityErrorResponse> authorityFailure(ViewOnlyAuthorityException failure) {
        ViewOnlyAuthorityHttpErrorMapper.MappedError mapped = mapper.map(failure);
        return ResponseEntity.status(mapped.httpStatus())
                .cacheControl(CacheControl.noStore())
                .body(mapped.body());
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ViewOnlyAuthorityErrorResponse> unsupportedMediaType(
            HttpMediaTypeNotSupportedException failure) {
        return explicitFailure(415, "CONTENT_TYPE_UNSUPPORTED",
                "authority request content type must be application/json");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ViewOnlyAuthorityErrorResponse> unsupportedMethod(
            HttpRequestMethodNotSupportedException failure) {
        return explicitFailure(405, "METHOD_NOT_ALLOWED",
                "authority request method is not allowed");
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            ServletRequestBindingException.class,
            com.example.endpointadmin.remoteaccess.policy.RemoteViewPolicyException.class
    })
    public ResponseEntity<ViewOnlyAuthorityErrorResponse> strictRequestFailure(Exception failure) {
        return authorityFailure(new ViewOnlyAuthorityException(
                ViewOnlyAuthorityError.CONTRACT_INVALID,
                "authority request could not be parsed under the exact contract"));
    }

    private ResponseEntity<ViewOnlyAuthorityErrorResponse> explicitFailure(
            int status, String code, String message) {
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .body(new ViewOnlyAuthorityErrorResponse(
                        "faz22.6.viewOnlyPreflightError.v1", java.util.UUID.randomUUID(),
                        code, message, false, 0, false));
    }
}
