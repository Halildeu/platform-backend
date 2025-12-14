package com.example.variant.theme.service;

import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.domain.ThemeAxes;
import com.example.variant.theme.domain.ThemeRegistryEditableBy;
import com.example.variant.theme.domain.ThemeRegistryEntry;
import com.example.variant.theme.domain.ThemeType;
import com.example.variant.theme.domain.UserThemeSelection;
import com.example.variant.theme.repository.ThemeRegistryEntryRepository;
import com.example.variant.theme.repository.ThemeRepository;
import com.example.variant.theme.repository.UserThemeSelectionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
// STORY-0022: Theme Personalization v1.0
public class ThemeService {

    private static final int MAX_USER_THEMES = 3;
    private static final String GLOBAL_DEFAULT_VISIBILITY = "DEFAULT";
    private static final String DEFAULT_ACCENT = "neutral";
    private static final String DEFAULT_DENSITY = "comfortable";
    private static final String DEFAULT_RADIUS = "rounded";
    private static final String DEFAULT_ELEVATION = "raised";
    private static final String DEFAULT_MOTION = "standard";

    private final ThemeRepository themeRepository;
    private final ThemeRegistryEntryRepository registryRepository;
    private final UserThemeSelectionRepository userThemeSelectionRepository;

    public ThemeService(ThemeRepository themeRepository,
                        ThemeRegistryEntryRepository registryRepository,
                        UserThemeSelectionRepository userThemeSelectionRepository) {
        this.themeRepository = themeRepository;
        this.registryRepository = registryRepository;
        this.userThemeSelectionRepository = userThemeSelectionRepository;
    }

    @Transactional(readOnly = true)
    public List<Theme> listGlobalThemes() {
        return themeRepository.findByType(ThemeType.GLOBAL);
    }

    @Transactional(readOnly = true)
    public List<Theme> listUserThemes(String ownerUserId) {
        return themeRepository.findByTypeAndOwnerUserId(ThemeType.USER, ownerUserId);
    }

    @Transactional(readOnly = true)
    public Theme getTheme(UUID themeId, String ownerUserId) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() == ThemeType.USER && ownerUserId != null && !ownerUserId.equals(theme.getOwnerUserId())) {
            throw new ThemeValidationException("FORBIDDEN_THEME_ACCESS");
        }
        return theme;
    }

    @Transactional
    public Theme createUserTheme(Theme prototype, String ownerUserId) {
        List<Theme> existing = listUserThemes(ownerUserId);
        if (existing.size() >= MAX_USER_THEMES) {
            throw new ThemeValidationException("USER_THEME_LIMIT_EXCEEDED");
        }
        prototype.setId(null);
        prototype.setType(ThemeType.USER);
        prototype.setGlobal(false);
        prototype.setOwnerUserId(ownerUserId);
        validateOverrides(prototype.getOverrides(), false);
        return themeRepository.save(prototype);
    }

    @Transactional
    public Theme updateUserTheme(UUID themeId, String ownerUserId, Map<String, String> overrides) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() != ThemeType.USER || !ownerUserId.equals(theme.getOwnerUserId())) {
            throw new ThemeValidationException("FORBIDDEN_THEME_ACCESS");
        }
        validateOverrides(overrides, false);
        theme.setOverrides(overrides);
        return themeRepository.save(theme);
    }

    @Transactional
    public void deleteUserTheme(UUID themeId, String ownerUserId) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() != ThemeType.USER || !ownerUserId.equals(theme.getOwnerUserId())) {
            throw new ThemeValidationException("FORBIDDEN_THEME_ACCESS");
        }
        themeRepository.delete(theme);
    }

    @Transactional
    public Theme forkFromGlobal(UUID globalThemeId, String ownerUserId) {
        Theme base = themeRepository.findById(globalThemeId)
            .orElseThrow(() -> new ThemeNotFoundException(globalThemeId));
        if (base.getType() != ThemeType.GLOBAL) {
            throw new ThemeValidationException("BASE_THEME_MUST_BE_GLOBAL");
        }
        Theme fork = new Theme();
        fork.setName(base.getName());
        fork.setType(ThemeType.USER);
        fork.setGlobal(false);
        fork.setOwnerUserId(ownerUserId);
        fork.setBaseThemeId(base.getId());
        fork.setAppearance(base.getAppearance());
        fork.setSurfaceTone(base.getSurfaceTone());
        fork.setAxes(base.getAxes());
        fork.setOverrides(Map.of());
        return createUserTheme(fork, ownerUserId);
    }

    @Transactional
    public Theme updateGlobalTheme(UUID themeId, Map<String, String> overrides) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() != ThemeType.GLOBAL) {
            throw new ThemeValidationException("THEME_MUST_BE_GLOBAL");
        }
        validateOverrides(overrides, true);
        theme.setOverrides(overrides);
        return themeRepository.save(theme);
    }

    @Transactional
    public Theme updateGlobalThemeMeta(UUID themeId,
                                       String appearance,
                                       String surfaceTone,
                                       String accent,
                                       String density,
                                       String radius,
                                       String elevation,
                                       String motion,
                                       Boolean activeFlag) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() != ThemeType.GLOBAL) {
            throw new ThemeValidationException("THEME_MUST_BE_GLOBAL");
        }
        if (appearance != null && !appearance.isBlank()) {
            theme.setAppearance(appearance.trim());
        }
        String normalizedSurfaceTone = surfaceTone != null && surfaceTone.isBlank() ? null : surfaceTone;
        theme.setSurfaceTone(normalizedSurfaceTone);

        if (theme.getAxes() == null) {
            theme.setAxes(new ThemeAxes());
        }
        ThemeAxes axes = theme.getAxes();
        axes.setAccent(normalizeBlankToNull(accent));
        axes.setDensity(normalizeBlankToNull(density));
        axes.setRadius(normalizeBlankToNull(radius));
        axes.setElevation(normalizeBlankToNull(elevation));
        axes.setMotion(normalizeBlankToNull(motion));
        theme.setAxes(axes);

        if (activeFlag != null) {
            theme.setActiveFlag(activeFlag);
        }

        return themeRepository.save(theme);
    }

    @Transactional
    public List<Theme> updateGlobalThemePalette(Map<UUID, Boolean> activeFlagsById) {
        if (activeFlagsById == null || activeFlagsById.isEmpty()) {
            throw new ThemeValidationException("PALETTE_ITEMS_REQUIRED");
        }
        for (Map.Entry<UUID, Boolean> entry : activeFlagsById.entrySet()) {
            if (entry.getKey() == null) {
                throw new ThemeValidationException("INVALID_THEME_ID");
            }
            if (entry.getValue() == null) {
                throw new ThemeValidationException("PALETTE_ACTIVE_FLAG_REQUIRED");
            }
        }

        List<Theme> themes = themeRepository.findAllById(activeFlagsById.keySet());
        if (themes.size() != activeFlagsById.size()) {
            throw new ThemeValidationException("INVALID_THEME_ID");
        }

        for (Theme theme : themes) {
            if (theme.getType() != ThemeType.GLOBAL) {
                throw new ThemeValidationException("THEME_MUST_BE_GLOBAL");
            }
            theme.setActiveFlag(activeFlagsById.get(theme.getId()));
        }

        List<Theme> saved = themeRepository.saveAll(themes);
        long activeCount = themeRepository.countByTypeAndActiveFlagTrue(ThemeType.GLOBAL);
        if (activeCount == 0L) {
            throw new ThemeValidationException("PALETTE_MUST_HAVE_AT_LEAST_ONE_ACTIVE_THEME");
        }

        return saved;
    }

    @Transactional(readOnly = true)
    public ResolvedTheme resolveThemeForUser(String ownerUserId) {
        Theme selected = null;

        // 1) Kullanıcı için açık bir seçim varsa onu kullan.
        UserThemeSelection selection = userThemeSelectionRepository.findById(ownerUserId).orElse(null);
        if (selection != null && selection.getThemeId() != null) {
            selected = themeRepository.findById(selection.getThemeId()).orElse(null);
            // Güvenlik: user theme ise owner eşleşmeli; eşleşmezse seçim yok sayılır.
            if (selected != null
                && selected.getType() == ThemeType.USER
                && !ownerUserId.equals(selected.getOwnerUserId())) {
                selected = null;
            }
        }

        // 2) Seçim yoksa, mevcut user temalarından ilkini kullan.
        if (selected == null) {
            List<Theme> userThemes = listUserThemes(ownerUserId);
            selected = userThemes.stream().findFirst().orElse(null);
        }

        // 3) Hâlâ bulunamadıysa, global varsayılan temaya düş.
        if (selected == null) {
            Theme defaultGlobal = themeRepository.findByTypeAndVisibility(ThemeType.GLOBAL, GLOBAL_DEFAULT_VISIBILITY)
                .stream()
                .findFirst()
                .orElse(null);
            if (defaultGlobal != null) {
                selected = defaultGlobal;
            } else {
                selected = themeRepository.findByType(ThemeType.GLOBAL).stream().findFirst()
                    .orElseThrow(() -> new ThemeValidationException("NO_THEME_DEFINED"));
            }
        }

        ResolvedTheme resolved = new ResolvedTheme();
        resolved.setThemeId(selected.getId().toString());
        resolved.setType(selected.getType().name());
        resolved.setAppearance(selected.getAppearance());
        resolved.setSurfaceTone(selected.getSurfaceTone());

        ResolvedTheme.ThemeAxesSnapshot axesSnapshot = new ResolvedTheme.ThemeAxesSnapshot();
        if (selected.getAxes() != null) {
            axesSnapshot.setAccent(defaultIfBlank(selected.getAxes().getAccent(), DEFAULT_ACCENT));
            axesSnapshot.setDensity(defaultIfBlank(selected.getAxes().getDensity(), DEFAULT_DENSITY));
            axesSnapshot.setRadius(defaultIfBlank(selected.getAxes().getRadius(), DEFAULT_RADIUS));
            axesSnapshot.setElevation(defaultIfBlank(selected.getAxes().getElevation(), DEFAULT_ELEVATION));
            axesSnapshot.setMotion(defaultIfBlank(selected.getAxes().getMotion(), DEFAULT_MOTION));
        } else {
            axesSnapshot.setAccent(DEFAULT_ACCENT);
            axesSnapshot.setDensity(DEFAULT_DENSITY);
            axesSnapshot.setRadius(DEFAULT_RADIUS);
            axesSnapshot.setElevation(DEFAULT_ELEVATION);
            axesSnapshot.setMotion(DEFAULT_MOTION);
        }
        resolved.setAxes(axesSnapshot);

        Map<String, String> effectiveOverrides = selected.getOverrides();
        Theme baseForMerge = null;
        if (selected.getType() == ThemeType.USER && selected.getBaseThemeId() != null) {
            Theme base = themeRepository.findById(selected.getBaseThemeId()).orElse(null);
            if (base != null && base.getType() == ThemeType.GLOBAL) {
                baseForMerge = base;
                Map<String, String> merged = new HashMap<>();
                if (base.getOverrides() != null) {
                    merged.putAll(base.getOverrides());
                }
                if (selected.getOverrides() != null) {
                    merged.putAll(selected.getOverrides());
                }
                effectiveOverrides = merged;
            }
        }

        resolved.setTokens(effectiveOverrides);
        resolved.setUpdatedAt(resolveResolvedUpdatedAt(selected, baseForMerge));
        resolved.setVersion(resolveResolvedVersion(selected, baseForMerge));
        return resolved;
    }

    @Transactional
    public void selectUserTheme(String ownerUserId, UUID themeId) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));

        if (theme.getType() == ThemeType.USER && !ownerUserId.equals(theme.getOwnerUserId())) {
            throw new ThemeValidationException("FORBIDDEN_THEME_ACCESS");
        }

        UserThemeSelection selection = userThemeSelectionRepository.findById(ownerUserId)
            .orElseGet(() -> {
                UserThemeSelection created = new UserThemeSelection();
                created.setUserId(ownerUserId);
                return created;
            });
        selection.setThemeId(themeId);
        userThemeSelectionRepository.save(selection);
    }

    @Transactional
    public Theme setGlobalDefaultTheme(UUID themeId) {
        Theme theme = themeRepository.findById(themeId)
            .orElseThrow(() -> new ThemeNotFoundException(themeId));
        if (theme.getType() != ThemeType.GLOBAL) {
            throw new ThemeValidationException("THEME_MUST_BE_GLOBAL");
        }

        List<Theme> currentDefaults = themeRepository.findByTypeAndVisibility(ThemeType.GLOBAL, GLOBAL_DEFAULT_VISIBILITY);
        for (Theme existing : currentDefaults) {
            if (existing.getId() == null || existing.getId().equals(themeId)) {
                continue;
            }
            existing.setVisibility(null);
            themeRepository.save(existing);
        }

        theme.setVisibility(GLOBAL_DEFAULT_VISIBILITY);
        return themeRepository.save(theme);
    }

    private void validateOverrides(Map<String, String> overrides, boolean isAdmin) {
        if (overrides == null || overrides.isEmpty()) {
            return;
        }
        Map<String, ThemeRegistryEntry> registryByKey = registryRepository.findAll()
            .stream()
            .collect(Collectors.toMap(ThemeRegistryEntry::getKey, it -> it));
        for (String key : overrides.keySet()) {
            ThemeRegistryEntry entry = registryByKey.get(key);
            if (entry == null) {
                throw new ThemeValidationException("UNKNOWN_REGISTRY_KEY: " + key);
            }
            if (!isAdmin && entry.getEditableBy() == ThemeRegistryEditableBy.ADMIN_ONLY) {
                throw new ThemeValidationException("NON_EDITABLE_KEY_FOR_USER: " + key);
            }
        }
    }

    private String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String defaultIfBlank(String value, String fallback) {
        String normalized = normalizeBlankToNull(value);
        return normalized != null ? normalized : fallback;
    }

    private Instant resolveThemeUpdatedAt(Theme theme) {
        if (theme == null) {
            return Instant.EPOCH;
        }
        Instant updated = theme.getUpdatedAt();
        if (updated != null) {
            return updated;
        }
        Instant created = theme.getCreatedAt();
        return created != null ? created : Instant.EPOCH;
    }

    private Instant resolveResolvedUpdatedAt(Theme selected, Theme base) {
        Instant selectedAt = resolveThemeUpdatedAt(selected);
        Instant baseAt = resolveThemeUpdatedAt(base);
        return selectedAt.isAfter(baseAt) ? selectedAt : baseAt;
    }

    private String resolveResolvedVersion(Theme selected, Theme base) {
        Instant selectedAt = resolveThemeUpdatedAt(selected);
        Instant baseAt = resolveThemeUpdatedAt(base);
        String baseId = base != null && base.getId() != null ? base.getId().toString() : "";
        return selected.getId() + ":" + selectedAt.toEpochMilli() + ":" + baseId + ":" + baseAt.toEpochMilli();
    }
}
