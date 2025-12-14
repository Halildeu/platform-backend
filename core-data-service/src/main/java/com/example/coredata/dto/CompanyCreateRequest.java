package com.example.coredata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CompanyCreateRequest(
        @NotBlank @Size(max = 64) String companyCode,
        @NotBlank @Size(max = 255) String companyName,
        @Size(max = 128) String shortName,
        @Size(max = 32) String companyType,
        @Size(max = 32) String taxNumber,
        @Size(max = 128) String taxOffice,
        @Size(max = 2) String countryCode,
        @Size(max = 128) String city,
        @NotBlank @Size(max = 16) String status
) {
}
