package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Browser-managed TPM/client-certificate renewal request.
 *
 * <p>The browser supplies no enrollment token, API URL, certificate filter or
 * device identity. The backend derives and protects those values server-side.
 */
public record CreateTpmCertificateRenewalRequest(
        @Size(max = 64)
        String idempotencyKey,

        @NotBlank
        @Size(max = 512)
        String reason) {
}
