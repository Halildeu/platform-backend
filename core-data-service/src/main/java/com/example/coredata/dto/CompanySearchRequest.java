package com.example.coredata.dto;

import jakarta.validation.constraints.Size;

public record CompanySearchRequest(
        @Size(max = 64) String companyCode,
        @Size(max = 255) String companyName,
        @Size(max = 16) String status
) {
}
