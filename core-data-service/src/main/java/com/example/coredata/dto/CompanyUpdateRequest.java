package com.example.coredata.dto;

import jakarta.validation.constraints.Size;

public record CompanyUpdateRequest(
        @Size(max = 255) String companyName,
        @Size(max = 128) String shortName,
        @Size(max = 32) String companyType,
        @Size(max = 32) String taxNumber,
        @Size(max = 128) String taxOffice,
        @Size(max = 2) String countryCode,
        @Size(max = 128) String city,
        @Size(max = 16) String status
) {
}
