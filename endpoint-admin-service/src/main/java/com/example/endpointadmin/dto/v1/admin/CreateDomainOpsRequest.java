package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateDomainOpsRequest(
        @NotBlank
        String operation,

        @NotBlank
        @Size(max = 512)
        String reason,

        Long ttlSeconds,

        @Size(max = 128)
        String idempotencyKey,

        @Size(max = 256)
        String credentialRef
) {
}
