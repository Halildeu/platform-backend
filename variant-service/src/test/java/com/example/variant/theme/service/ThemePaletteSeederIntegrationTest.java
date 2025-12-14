package com.example.variant.theme.service;

import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.domain.ThemeType;
import com.example.variant.theme.repository.ThemeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class ThemePaletteSeederIntegrationTest {

    @Autowired
    private ThemeRepository themeRepository;

    @Test
    void seedsPaletteGlobalThemes() {
        List<Theme> globals = themeRepository.findByType(ThemeType.GLOBAL);
        long defaultCount = globals.stream().filter((theme) -> "DEFAULT".equalsIgnoreCase(theme.getVisibility())).count();
        assertThat(defaultCount).isEqualTo(1);

        Map<String, List<Theme>> byAccent = globals.stream()
            .filter((theme) -> theme.getAxes() != null && theme.getAxes().getAccent() != null)
            .collect(Collectors.groupingBy((theme) -> theme.getAxes().getAccent().trim().toLowerCase(Locale.ROOT)));

        List<String> expectedAccents = List.of("neutral", "light", "violet", "emerald", "sunset", "ocean", "graphite");
        expectedAccents.forEach((accent) -> assertThat(byAccent).containsKey(accent));

        expectedAccents.forEach((accent) -> {
            Theme theme = byAccent.get(accent).getFirst();
            assertThat(theme.isGlobal()).isTrue();
            assertThat(theme.getAppearance()).isEqualTo("light");
            assertThat(theme.getSurfaceTone()).isNotBlank();
            assertThat(theme.getAxes()).isNotNull();
            assertThat(theme.getAxes().getDensity()).isNotBlank();
            assertThat(theme.getAxes().getRadius()).isNotBlank();
            assertThat(theme.getAxes().getElevation()).isNotBlank();
            assertThat(theme.getAxes().getMotion()).isNotBlank();
            boolean shouldBeVisible = !"neutral".equals(accent);
            assertThat(theme.getActiveFlag()).isEqualTo(shouldBeVisible);
        });

    }
}
