package com.example.endpointadmin.dto.v1.admin;

import java.time.Instant;
import java.util.UUID;

public class CreateEndpointEnrollmentResponse {

    private final UUID enrollmentId;
    private final String token;
    private final Instant expiresAt;
    private final UUID deviceId;

    public CreateEndpointEnrollmentResponse(UUID enrollmentId, String token, Instant expiresAt) {
        this(enrollmentId, token, expiresAt, null);
    }

    public CreateEndpointEnrollmentResponse(UUID enrollmentId, String token, Instant expiresAt, UUID deviceId) {
        this.enrollmentId = enrollmentId;
        this.token = token;
        this.expiresAt = expiresAt;
        this.deviceId = deviceId;
    }

    public UUID getEnrollmentId() {
        return enrollmentId;
    }

    public String getToken() {
        return token;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    @Override
    public String toString() {
        return "CreateEndpointEnrollmentResponse{"
                + "enrollmentId=" + enrollmentId
                + ", token='[REDACTED]'"
                + ", expiresAt=" + expiresAt
                + ", deviceId=" + deviceId
                + '}';
    }
}
