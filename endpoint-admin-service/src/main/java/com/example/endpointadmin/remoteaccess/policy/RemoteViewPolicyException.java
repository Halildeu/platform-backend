package com.example.endpointadmin.remoteaccess.policy;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
public final class RemoteViewPolicyException extends RuntimeException {
    private final RemoteViewPolicyReason reason;

    public RemoteViewPolicyException(RemoteViewPolicyReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public RemoteViewPolicyException(RemoteViewPolicyReason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public RemoteViewPolicyReason reason() {
        return reason;
    }
}
