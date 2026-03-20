package com.example.report.registry;

import java.util.Map;

public record DrillToSpec(
        String reportKey,
        Map<String, Object> filters,
        String filterColumn,
        String filterFromField
) {}
