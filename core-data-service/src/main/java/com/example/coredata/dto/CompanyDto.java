package com.example.coredata.dto;

import java.time.OffsetDateTime;

public record CompanyDto(
        Long id,
        String companyCode,
        String companyName,
        String shortName,
        String companyType,
        String taxNumber,
        String taxOffice,
        String countryCode,
        String city,
        String status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
