package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateEndpointEnrollmentRequest(
        @Min(1)
        @Max(10080)
        Integer expiresInMinutes,

        @Size(max = 512)
        String note,

        UUID deviceId
) {
    public CreateEndpointEnrollmentRequest(Integer expiresInMinutes, String note) {
        this(expiresInMinutes, note, null);
    }
}
