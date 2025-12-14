package com.example.variant;

import com.example.variant.model.GridVariant;
import com.example.variant.repository.GridVariantRepository;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class VariantSortOrderInitializer {

    private final GridVariantRepository repository;

    public VariantSortOrderInitializer(GridVariantRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void normalizeSortOrders() {
        List<GridVariant> allVariants = repository.findAll();
        if (allVariants.isEmpty()) {
            return;
        }

        List<GridVariant> toUpdate = new ArrayList<>();

        Map<String, List<GridVariant>> byGrid = allVariants.stream()
                .collect(Collectors.groupingBy(GridVariant::getGridId));

        for (List<GridVariant> gridVariants : byGrid.values()) {
            List<GridVariant> globalVariants = gridVariants.stream()
                    .filter(GridVariant::isGlobal)
                    .toList();
            toUpdate.addAll(normalizeGroup(globalVariants));

            Map<Long, List<GridVariant>> byUser = gridVariants.stream()
                    .filter(v -> !v.isGlobal())
                    .collect(Collectors.groupingBy(GridVariant::getUserId));

            for (List<GridVariant> userVariants : byUser.values()) {
                toUpdate.addAll(normalizeGroup(userVariants));
            }
        }

        if (!toUpdate.isEmpty()) {
            repository.saveAll(toUpdate);
        }
    }

    private List<GridVariant> normalizeGroup(List<GridVariant> variants) {
        if (variants.isEmpty()) {
            return List.of();
        }

        Set<Integer> seen = new HashSet<>();
        boolean needsNormalization = false;
        for (GridVariant variant : variants) {
            Integer order = variant.getSortOrder();
            if (order == null || order < 0 || !seen.add(order)) {
                needsNormalization = true;
                break;
            }
        }

        if (!needsNormalization) {
            return List.of();
        }

        List<GridVariant> sortedByCreatedAt = variants.stream()
                .sorted(Comparator.comparing(GridVariant::getCreatedAt))
                .toList();

        List<GridVariant> updated = new ArrayList<>();
        for (int i = 0; i < sortedByCreatedAt.size(); i++) {
            GridVariant variant = sortedByCreatedAt.get(i);
            if (!Integer.valueOf(i).equals(variant.getSortOrder())) {
                variant.setSortOrder(i);
                updated.add(variant);
            }
        }
        return updated;
    }
}
