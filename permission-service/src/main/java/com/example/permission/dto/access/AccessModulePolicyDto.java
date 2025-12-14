package com.example.permission.dto.access;

public record AccessModulePolicyDto(
        String moduleKey,
        String moduleLabel,
        String level,
        String lastUpdatedAt,
        String updatedBy
) {}

