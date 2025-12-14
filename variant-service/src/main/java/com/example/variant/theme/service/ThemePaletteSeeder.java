package com.example.variant.theme.service;

import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.domain.ThemeAxes;
import com.example.variant.theme.domain.ThemeType;
import com.example.variant.theme.repository.ThemeRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;
import java.util.Comparator;
import java.util.function.Predicate;

@Component
// STORY-0022: Theme Personalization v1.0
public class ThemePaletteSeeder implements ApplicationRunner {

    private static final String DEFAULT_APPEARANCE = "light";
    private static final String GLOBAL_DEFAULT_VISIBILITY = "DEFAULT";
    private static final String DEFAULT_SURFACE_TONE = "ultra-1";
    private static final String DEFAULT_DENSITY = "comfortable";
    private static final String DEFAULT_RADIUS = "rounded";
    private static final String DEFAULT_ELEVATION = "raised";
    private static final String DEFAULT_MOTION = "standard";

    private static final Map<String, UUID> PALETTE_THEME_IDS = Map.of(
        "neutral", UUID.fromString("00000000-0000-0000-0000-000000000108"),
        "light", UUID.fromString("00000000-0000-0000-0000-000000000101"),
        "violet", UUID.fromString("00000000-0000-0000-0000-000000000103"),
        "emerald", UUID.fromString("00000000-0000-0000-0000-000000000104"),
        "sunset", UUID.fromString("00000000-0000-0000-0000-000000000105"),
        "ocean", UUID.fromString("00000000-0000-0000-0000-000000000106"),
        "graphite", UUID.fromString("00000000-0000-0000-0000-000000000107")
    );

    private static final List<PaletteThemeSpec> PALETTE_THEMES = List.of(
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("neutral"), "Global Neutral", "neutral", false),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("light"), "Global Light", "light", true),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("violet"), "Global Violet", "violet", true),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("emerald"), "Global Emerald", "emerald", true),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("sunset"), "Global Sunset", "sunset", true),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("ocean"), "Global Ocean", "ocean", true),
        new PaletteThemeSpec(PALETTE_THEME_IDS.get("graphite"), "Global Graphite", "graphite", true)
    );

    private final ThemeRepository themeRepository;

    public ThemePaletteSeeder(ThemeRepository themeRepository) {
        this.themeRepository = themeRepository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Theme> globals = themeRepository.findByType(ThemeType.GLOBAL);
        for (PaletteThemeSpec spec : PALETTE_THEMES) {
            Theme match = findExistingPaletteTheme(globals, spec);
            if (match == null) {
                themeRepository.save(createTheme(spec));
                continue;
            }
            ensureThemeMatchesPaletteSpec(match, spec);
            themeRepository.save(match);
        }

        List<Theme> defaults = themeRepository.findByTypeAndVisibility(ThemeType.GLOBAL, GLOBAL_DEFAULT_VISIBILITY);
        if (defaults.size() > 1) {
            UUID preferredId = PALETTE_THEME_IDS.get("light");
            Theme keep = defaults.stream()
                .filter((theme) -> preferredId != null && preferredId.equals(theme.getId()))
                .findFirst()
                .orElseGet(() -> defaults.stream()
                    .sorted(Comparator.comparing(Theme::getName, String.CASE_INSENSITIVE_ORDER))
                    .findFirst()
                    .orElse(null));
            if (keep != null && keep.getId() != null) {
                UUID keepId = keep.getId();
                for (Theme theme : defaults) {
                    if (theme.getId() != null && !theme.getId().equals(keepId)) {
                        theme.setVisibility(null);
                        themeRepository.save(theme);
                    }
                }
            }
            return;
        }

        if (defaults.isEmpty()) {
            UUID fallbackId = PALETTE_THEME_IDS.get("light");
            Theme fallback = fallbackId != null ? themeRepository.findById(fallbackId).orElse(null) : null;
            if (fallback == null) {
                fallback = themeRepository.findByType(ThemeType.GLOBAL).stream().findFirst().orElse(null);
            }
            if (fallback != null && (fallback.getVisibility() == null || fallback.getVisibility().isBlank())) {
                fallback.setVisibility(GLOBAL_DEFAULT_VISIBILITY);
                themeRepository.save(fallback);
            }
        }
    }

    private Theme findExistingPaletteTheme(List<Theme> globals, PaletteThemeSpec spec) {
        Theme byId = globals.stream()
            .filter((theme) -> spec.id().equals(theme.getId()))
            .findFirst()
            .orElse(null);
        if (byId != null) {
            return byId;
        }

        String accent = normalize(spec.accent());
        Theme byAccent = globals.stream()
            .filter(withAppearance(DEFAULT_APPEARANCE).and(withAccent(accent)))
            .findFirst()
            .orElse(null);
        if (byAccent != null) {
            return byAccent;
        }

        String expectedName = normalize(spec.name());
        return globals.stream()
            .filter((theme) -> normalize(theme.getName()).equals(expectedName))
            .findFirst()
            .orElse(null);
    }

    private void ensureThemeMatchesPaletteSpec(Theme theme, PaletteThemeSpec spec) {
        if (theme.getType() != ThemeType.GLOBAL) {
            return;
        }
        theme.setGlobal(true);
        if (isBlank(theme.getAppearance())) {
            theme.setAppearance(DEFAULT_APPEARANCE);
        }
        if (theme.getAxes() == null) {
            theme.setAxes(new ThemeAxes());
        }
        ThemeAxes axes = theme.getAxes();
        axes.setAccent(spec.accent());
        if (isBlank(axes.getDensity())) {
            axes.setDensity(DEFAULT_DENSITY);
        }
        if (isBlank(axes.getRadius())) {
            axes.setRadius(DEFAULT_RADIUS);
        }
        if (isBlank(axes.getElevation())) {
            axes.setElevation(DEFAULT_ELEVATION);
        }
        if (isBlank(axes.getMotion())) {
            axes.setMotion(DEFAULT_MOTION);
        }
        theme.setAxes(axes);
        if (isBlank(theme.getSurfaceTone())) {
            theme.setSurfaceTone(DEFAULT_SURFACE_TONE);
        }

        if (theme.getActiveFlag() == null) {
            theme.setActiveFlag(spec.paletteVisibleDefault());
        }
    }

    private Theme createTheme(PaletteThemeSpec spec) {
        Theme theme = new Theme();
        theme.setId(spec.id());
        theme.setName(spec.name());
        theme.setType(ThemeType.GLOBAL);
        theme.setGlobal(true);
        theme.setAppearance(DEFAULT_APPEARANCE);
        theme.setSurfaceTone(DEFAULT_SURFACE_TONE);

        ThemeAxes axes = new ThemeAxes();
        axes.setAccent(spec.accent());
        axes.setDensity(DEFAULT_DENSITY);
        axes.setRadius(DEFAULT_RADIUS);
        axes.setElevation(DEFAULT_ELEVATION);
        axes.setMotion(DEFAULT_MOTION);
        theme.setAxes(axes);

        theme.setOverrides(new HashMap<>());
        theme.setActiveFlag(spec.paletteVisibleDefault());
        return theme;
    }

    private Predicate<Theme> withAccent(String expected) {
        return (theme) -> normalize(theme.getAxes() != null ? theme.getAxes().getAccent() : null).equals(expected);
    }

    private Predicate<Theme> withAppearance(String expected) {
        return (theme) -> normalize(theme.getAppearance()).equals(expected);
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isBlank();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private record PaletteThemeSpec(UUID id, String name, String accent, boolean paletteVisibleDefault) {
    }
}
