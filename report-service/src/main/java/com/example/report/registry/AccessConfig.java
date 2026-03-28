package com.example.report.registry;

import java.util.List;
import java.util.Map;

public record AccessConfig(
        String permission,
        Map<String, List<String>> columnRestrictions,
        RowFilter rowFilter
) {
    public record RowFilter(
            String column,
            String scopeType,
            String bypassPermission
    ) {}
}
