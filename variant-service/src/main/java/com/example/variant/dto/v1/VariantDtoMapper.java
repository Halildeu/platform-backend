package com.example.variant.dto.v1;

import com.example.variant.dto.VariantResponse;

public final class VariantDtoMapper {
    private VariantDtoMapper() {
    }

    public static VariantDto toDto(VariantResponse response) {
        if (response == null) return null;
        VariantDto dto = new VariantDto();
        dto.setId(response.getId());
        dto.setGridId(response.getGridId());
        dto.setName(response.getName());
        dto.setDefault(response.isDefault());
        dto.setGlobal(response.isGlobal());
        dto.setGlobalDefault(response.isGlobalDefault());
        dto.setState(response.getState());
        dto.setSchemaVersion(response.getSchemaVersion());
        dto.setCompatible(response.isCompatible());
        dto.setSortOrder(response.getSortOrder());
        dto.setCreatedAt(response.getCreatedAt());
        dto.setUpdatedAt(response.getUpdatedAt());
        dto.setUserDefault(response.isUserDefault());
        dto.setUserSelected(response.isUserSelected());
        return dto;
    }
}
