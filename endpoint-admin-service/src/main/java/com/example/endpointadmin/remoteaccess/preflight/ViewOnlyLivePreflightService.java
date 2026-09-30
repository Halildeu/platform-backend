package com.example.endpointadmin.remoteaccess.preflight;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Fixed-function, zero-mutation 12-check attestor boundary. No placeholder bean
 * is supplied: enabling the authority without the real implementation fails
 * application startup.
 */
@FunctionalInterface
public interface ViewOnlyLivePreflightService {
    byte[] attest(byte[] rawRequest, Jwt caller);
}
