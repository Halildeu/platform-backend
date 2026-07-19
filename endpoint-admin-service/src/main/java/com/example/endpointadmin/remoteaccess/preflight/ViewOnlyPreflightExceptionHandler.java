package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
}
