package com.example.variant.dto.v1;

import java.util.List;

public record VariantPresetListResponse(
        String gridId,
        List<VariantPresetDto> presets
) {
}
