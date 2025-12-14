package com.example.variant.service;

import com.example.variant.dto.v1.VariantPresetDto;
import com.example.variant.dto.v1.VariantPresetListResponse;
import com.example.variant.model.GridVariant;
import com.example.variant.model.VariantVisibility;
import com.example.variant.repository.VariantVisibilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class VariantPresetService {

    private final VariantVisibilityRepository visibilityRepository;

    public VariantPresetService(VariantVisibilityRepository visibilityRepository) {
        this.visibilityRepository = visibilityRepository;
    }

    @Transactional(readOnly = true)
    public VariantPresetListResponse listPresets(String gridId, String companyId, Long userId) {
        List<VariantPresetDto> result = new ArrayList<>();

        // Global presetler
        visibilityRepository.findGlobalByGrid(gridId, VariantVisibility.VisibilityType.GLOBAL)
                .forEach(vis -> result.add(toDto(vis, "GLOBAL")));

        // Company presetleri
        if (companyId != null && !companyId.isBlank()) {
            visibilityRepository.findByGridAndTypeAndRef(gridId, VariantVisibility.VisibilityType.COMPANY, companyId)
                    .forEach(vis -> result.add(toDto(vis, "COMPANY")));
        }

        // Kullanıcı presetleri
        if (userId != null) {
            visibilityRepository.findByGridAndTypeAndRef(gridId, VariantVisibility.VisibilityType.USER, String.valueOf(userId))
                    .forEach(vis -> result.add(toDto(vis, "USER")));
        }

        // Basit bir sıralama: USER > COMPANY > GLOBAL > ROLE (şimdilik ROLE eklenmiyor)
        result.sort(Comparator.comparing((VariantPresetDto dto) -> order(dto.level()))
                .thenComparing(VariantPresetDto::name));

        return new VariantPresetListResponse(gridId, result);
    }

    private VariantPresetDto toDto(VariantVisibility vis, String levelOverride) {
        GridVariant variant = vis.getVariant();
        String level = levelOverride != null ? levelOverride : vis.getVisibilityType().name();
        return new VariantPresetDto(
                variant.getId(),
                variant.getGridId(),
                variant.getName(),
                level,
                vis.getRefId(),
                variant.isDefault() || variant.isGlobalDefault(),
                variant.getStateJson()
        );
    }

    private int order(String level) {
        if (level == null) return 99;
        return switch (level.toUpperCase()) {
            case "USER" -> 0;
            case "COMPANY" -> 1;
            case "ROLE" -> 2;
            case "GLOBAL" -> 3;
            default -> 99;
        };
    }
}
