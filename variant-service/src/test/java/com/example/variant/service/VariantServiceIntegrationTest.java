package com.example.variant.service;

import com.example.variant.dto.UpdateVariantRequest;
import com.example.variant.dto.VariantPreferenceUpdateRequest;
import com.example.variant.dto.VariantResponse;
import com.example.variant.model.GridVariant;
import com.example.variant.model.PreferenceType;
import com.example.variant.repository.GridVariantPreferenceRepository;
import com.example.variant.repository.GridVariantRepository;
import com.example.variant.security.AuthenticatedUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Entegrasyon testi: global varyant için kullanıcı bazlı varsayılan seçeneğinin
 * backend tarafından doğru şekilde tutulduğunu garanti eder.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class VariantServiceIntegrationTest {

    private static final String GRID_ID = "test-grid";

    @Autowired
    private VariantService variantService;

    @Autowired
    private GridVariantRepository gridVariantRepository;

    @Autowired
    private GridVariantPreferenceRepository gridVariantPreferenceRepository;

    private AuthenticatedUser adminUser;
    private GridVariant globalVariant;
    private GridVariant personalVariant;

    @BeforeEach
    void setUp() {
        adminUser = new AuthenticatedUser(1L, "admin@example.com", "ADMIN", null, java.util.List.of());

        globalVariant = new GridVariant();
        globalVariant.setId(UUID.randomUUID());
        globalVariant.setUserId(adminUser.id());
        globalVariant.setGridId(GRID_ID);
        globalVariant.setName("Global Varyant");
        globalVariant.setGlobal(true);
        globalVariant.setGlobalDefault(false);
        globalVariant.setDefault(false);
        globalVariant.setSchemaVersion(1);
        globalVariant.setStateJson("{}");
        globalVariant.setCompatible(true);
        globalVariant.setSortOrder(0);
        globalVariant.setCreatedAt(LocalDateTime.now());
        globalVariant.setUpdatedAt(LocalDateTime.now());

        gridVariantRepository.save(globalVariant);

        personalVariant = new GridVariant();
        personalVariant.setId(UUID.randomUUID());
        personalVariant.setUserId(adminUser.id());
        personalVariant.setGridId(GRID_ID);
        personalVariant.setName("Kişisel Varyant");
        personalVariant.setGlobal(false);
        personalVariant.setGlobalDefault(false);
        personalVariant.setDefault(true);
        personalVariant.setSchemaVersion(1);
        personalVariant.setStateJson("{}");
        personalVariant.setCompatible(true);
        personalVariant.setSortOrder(1);
        personalVariant.setCreatedAt(LocalDateTime.now());
        personalVariant.setUpdatedAt(LocalDateTime.now());

        gridVariantRepository.save(personalVariant);
    }

    @Test
    @Transactional
    void userDefaultToggleForGlobalVariantShouldPersistAndBeReflectedInResponses() {
        VariantPreferenceUpdateRequest makeDefaultRequest = new VariantPreferenceUpdateRequest();
        makeDefaultRequest.setDefault(true);
        makeDefaultRequest.setSelected(true);

        VariantResponse response = variantService.updateVariantPreference(adminUser, globalVariant.getId(), makeDefaultRequest);

        assertThat(response.isUserDefault()).isTrue();
        assertThat(gridVariantPreferenceRepository.findAll())
                .hasSize(1)
                .allSatisfy(pref -> {
                    assertThat(pref.getPreferenceType().name()).isEqualTo("GLOBAL_DEFAULT_OVERRIDE");
                    assertThat(pref.isDefault()).isTrue();
                    assertThat(pref.getVariant().getId()).isEqualTo(globalVariant.getId());
                });

        GridVariant reloadedPersonal = gridVariantRepository.findById(personalVariant.getId()).orElseThrow();
        assertThat(reloadedPersonal.isDefault()).isFalse();
        assertThat(gridVariantPreferenceRepository.findByUserIdAndGridIdAndPreferenceType(
                adminUser.id(), GRID_ID, PreferenceType.PERSONAL_DEFAULT)).isEmpty();

        VariantResponse fetched = variantService.getVariants(adminUser, GRID_ID).stream()
                .filter(variant -> variant.getId().equals(globalVariant.getId()))
                .findFirst()
                .orElseThrow();

        assertThat(fetched.isUserDefault()).isTrue();

        VariantPreferenceUpdateRequest removeDefaultRequest = new VariantPreferenceUpdateRequest();
        removeDefaultRequest.setDefault(false);

        VariantResponse cleared = variantService.updateVariantPreference(adminUser, globalVariant.getId(), removeDefaultRequest);
        assertThat(cleared.isUserDefault()).isFalse();
        assertThat(gridVariantPreferenceRepository.findAll()).isEmpty();

        UpdateVariantRequest makePersonalDefault = new UpdateVariantRequest();
        makePersonalDefault.setDefault(true);
        VariantResponse personalResponse = variantService.updateVariant(adminUser, personalVariant.getId(), makePersonalDefault);
        assertThat(personalResponse.isDefault()).isTrue();
        assertThat(gridVariantPreferenceRepository.findByUserIdAndGridIdAndPreferenceType(
                adminUser.id(), GRID_ID, PreferenceType.GLOBAL_DEFAULT_OVERRIDE)).isEmpty();
    }
}
