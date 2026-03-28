package com.example.report.registry;

import java.util.List;

public record LayoutConfig(
        List<LayoutSection> sections
) {
    public record LayoutSection(
            String type,
            List<String> ids
    ) {}
}
