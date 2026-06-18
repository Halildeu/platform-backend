package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Map;

public record SubmitDomainOpsResultRequest(
        @NotBlank
        String status,

        @Size(max = 128)
        String reasonCode,

        @Size(max = 128)
        String connectorAttemptId,

        @Size(max = 64)
        String packageSha256,

        Map<String, Object> result
) {
}
