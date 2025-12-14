package com.example.variant.theme.service;

import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.domain.ThemeRegistryEditableBy;
import com.example.variant.theme.domain.ThemeRegistryEntry;
import com.example.variant.theme.domain.ThemeType;
import com.example.variant.theme.repository.ThemeRegistryEntryRepository;
import com.example.variant.theme.repository.ThemeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ThemeServiceIntegrationTest {

    @Autowired
    private ThemeService themeService;

    @Autowired
    private ThemeRepository themeRepository;

    @Autowired
    private ThemeRegistryEntryRepository registryRepository;

    private Theme globalTheme;

    private String userId;

    @BeforeEach
    void setUp() {
        userId = "user-1";

        ThemeRegistryEntry bgEntry = new ThemeRegistryEntry();
        bgEntry.setKey("surface.default.bg");
        bgEntry.setLabel("Surface Default Background");
        bgEntry.setGroupName("surface");
        bgEntry.setControlType(com.example.variant.theme.domain.ThemeRegistryControlType.COLOR);
        bgEntry.setEditableBy(ThemeRegistryEditableBy.USER_ALLOWED);
        registryRepository.save(bgEntry);

        ThemeRegistryEntry dangerEntry = new ThemeRegistryEntry();
        dangerEntry.setKey("action.danger");
        dangerEntry.setLabel("Danger");
        dangerEntry.setGroupName("erpAction");
        dangerEntry.setControlType(com.example.variant.theme.domain.ThemeRegistryControlType.COLOR);
        dangerEntry.setEditableBy(ThemeRegistryEditableBy.ADMIN_ONLY);
        registryRepository.save(dangerEntry);

        globalTheme = new Theme();
        globalTheme.setName("Global Light");
        globalTheme.setType(ThemeType.GLOBAL);
        globalTheme.setGlobal(true);
        globalTheme.setAppearance("light");
        themeRepository.save(globalTheme);
    }

    @Test
    void createUserTheme_should_enforce_limit_and_registry_whitelist() {
        for (int i = 0; i < 3; i++) {
            Theme userTheme = new Theme();
            userTheme.setName("User Theme " + i);
            userTheme.setAppearance("light");
            userTheme.setType(ThemeType.USER);
            userTheme.setOverrides(Map.of("surface.default.bg", "#ffffff"));
            Theme created = themeService.createUserTheme(userTheme, userId);
            assertThat(created.getId()).isNotNull();
        }

        Theme extra = new Theme();
        extra.setName("Extra");
        extra.setAppearance("light");
        extra.setType(ThemeType.USER);
        extra.setOverrides(Map.of("surface.default.bg", "#ffffff"));

        assertThatThrownBy(() -> themeService.createUserTheme(extra, userId))
            .isInstanceOf(ThemeValidationException.class)
            .hasMessageContaining("USER_THEME_LIMIT_EXCEEDED");
    }

    @Test
    void updateUserTheme_should_reject_non_editable_registry_keys_for_user() {
        Theme userTheme = new Theme();
        userTheme.setName("User Theme");
        userTheme.setAppearance("light");
        userTheme.setType(ThemeType.USER);
        userTheme.setOverrides(Map.of("surface.default.bg", "#ffffff"));

        Theme created = themeService.createUserTheme(userTheme, userId);

        Map<String, String> invalidOverrides = Map.of(
            "surface.default.bg", "#ffffff",
            "action.danger", "#ff0000"
        );

        assertThatThrownBy(() -> themeService.updateUserTheme(created.getId(), userId, invalidOverrides))
            .isInstanceOf(ThemeValidationException.class)
            .hasMessageContaining("NON_EDITABLE_KEY_FOR_USER");
    }

    @Test
    void updateGlobalTheme_as_admin_should_allow_admin_only_keys() {
        Theme global = new Theme();
        global.setName("Global Danger");
        global.setType(ThemeType.GLOBAL);
        global.setGlobal(true);
        global.setAppearance("light");
        Theme saved = themeRepository.save(global);

        Map<String, String> overrides = Map.of(
            "action.danger", "#ff0000"
        );

        Theme updated = themeService.updateGlobalTheme(saved.getId(), overrides);

        assertThat(updated.getOverrides()).containsEntry("action.danger", "#ff0000");
    }

    @Test
    void resolveThemeForUser_should_fall_back_to_global_when_no_user_theme() {
        String anotherUser = "user-2";

        ResolvedTheme resolved = themeService.resolveThemeForUser(anotherUser);

        assertThat(resolved.getThemeId()).isNotNull();
        assertThat(resolved.getType()).isEqualTo("GLOBAL");
    }

    @Test
    void selectUserTheme_should_prefer_explicit_selection_over_first_user_theme() {
        Theme userTheme1 = new Theme();
        userTheme1.setName("User Theme 1");
        userTheme1.setAppearance("light");
        userTheme1.setType(ThemeType.USER);
        userTheme1.setOverrides(Map.of("surface.default.bg", "#ffffff"));
        Theme created1 = themeService.createUserTheme(userTheme1, userId);

        Theme userTheme2 = new Theme();
        userTheme2.setName("User Theme 2");
        userTheme2.setAppearance("light");
        userTheme2.setType(ThemeType.USER);
        userTheme2.setOverrides(Map.of("surface.default.bg", "#ffffff"));
        Theme created2 = themeService.createUserTheme(userTheme2, userId);

        ResolvedTheme before = themeService.resolveThemeForUser(userId);
        assertThat(before.getThemeId()).isEqualTo(created1.getId().toString());

        themeService.selectUserTheme(userId, created2.getId());

        ResolvedTheme after = themeService.resolveThemeForUser(userId);
        assertThat(after.getThemeId()).isEqualTo(created2.getId().toString());
        assertThat(after.getType()).isEqualTo("USER");
    }

    @Test
    void selectUserTheme_should_allow_global_theme_for_any_user() {
        String anotherUser = "user-2";

        themeService.selectUserTheme(anotherUser, globalTheme.getId());

        ResolvedTheme resolved = themeService.resolveThemeForUser(anotherUser);
        assertThat(resolved.getThemeId()).isEqualTo(globalTheme.getId().toString());
        assertThat(resolved.getType()).isEqualTo("GLOBAL");
    }

    @Test
    void selectUserTheme_should_reject_user_theme_not_owned_by_user() {
        Theme foreignUserTheme = new Theme();
        foreignUserTheme.setName("Foreign User Theme");
        foreignUserTheme.setAppearance("light");
        foreignUserTheme.setType(ThemeType.USER);
        foreignUserTheme.setOverrides(Map.of("surface.default.bg", "#ffffff"));
        Theme created = themeService.createUserTheme(foreignUserTheme, "owner-1");

        assertThatThrownBy(() -> themeService.selectUserTheme("other-user", created.getId()))
            .isInstanceOf(ThemeValidationException.class)
            .hasMessageContaining("FORBIDDEN_THEME_ACCESS");
    }
}
