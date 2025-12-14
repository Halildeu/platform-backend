package com.example.variant.dto.v1;

public record VariantPresetDto(
        java.util.UUID id,
        String gridId,
        String name,
        String level,      // GLOBAL / COMPANY / USER / ROLE
        String scopeRefId, // companyId / userId / roleId
        boolean isDefault,
        String configJson
) {
}
