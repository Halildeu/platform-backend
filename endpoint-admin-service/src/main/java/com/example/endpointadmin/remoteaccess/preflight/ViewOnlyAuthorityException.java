package com.example.endpointadmin.remoteaccess.preflight;

/** Fail-closed domain exception; messages must never contain bearer or envelope material. */
public final class ViewOnlyAuthorityException extends RuntimeException {
    private final ViewOnlyAuthorityError reason;

    public ViewOnlyAuthorityException(ViewOnlyAuthorityError reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ViewOnlyAuthorityException(ViewOnlyAuthorityError reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public ViewOnlyAuthorityError reason() {
        return reason;
    }
}
