package com.example.commonauth.identity;

/**
 * The canonical identity authority could not be consulted (board #2532).
 *
 * <p>Deliberately distinct from "no such user": callers MUST map this to {@code 503}, never to a
 * deny-by-default that then falls back to unverified claims. Falling back to the raw {@code sub}
 * when user-service is down is exactly how the KC-UUID-as-subject bug reached production paths.
 */
public class UserIdentityDirectoryUnavailableException extends RuntimeException {

    public UserIdentityDirectoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public UserIdentityDirectoryUnavailableException(String message) {
        super(message);
    }
}
