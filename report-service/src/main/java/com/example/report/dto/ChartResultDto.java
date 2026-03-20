package com.example.report.dto;

import com.example.report.registry.DrillToSpec;

import java.util.List;
import java.util.Map;

public record ChartResultDto(
        String id,
        String title,
        String chartType,
        String size,
        List<Map<String, Object>> data,
        Map<String, Object> chartConfig,
        DrillToSpec drillTo
) {}
