package com.example.user.controller;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.user.UserApplication;
import com.example.user.config.TestSecurityConfig;
import com.example.user.model.User;
import com.example.user.repository.UserRepository;
import com.example.user.security.ServiceAuthenticationToken;
import com.example.user.serviceauth.ServiceTokenVerifier;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Faz 24 (gitops#3834) — the people picker behind "Göreve ata".
 *
 * <p>A non-admin user could not assign a task because the picker called the admin user grid
 * ({@code GET /api/v1/users}, {@code USER_READ}) and got 403. This surface answers the picker
 * question and nothing more: who can own an assignment, visible to this requester, matching the
 * typed text — as id, name and email only.
 *
 * <p>Visibility mirrors {@code UserService#searchUsers} for a non-admin: global users
 * ({@code company_id IS NULL}) plus the requester's own company. A user of another company, a
 * disabled or deleted user, and a user with no Keycloak binding (who could never see the task in
 * "Görevlerim") are never offered.
 */
@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-assigneecandidates;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class AssigneeCandidatesInternalEndpointTest {

    private static final long OTHER_COMPANY = 35L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ServiceTokenVerifier serviceTokenVerifier;

    private String globalRequester;
    private String companyRequester;
    private String disabledRequester;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        globalRequester = save("Halil Global", "halil.global@example.com", null, true, false, true);
        companyRequester = save("Ahmet Otuzbeş", "ahmet.35@example.com", OTHER_COMPANY, true, false, true);
        disabledRequester = save("Pasif İstekçi", "pasif.istekci@example.com", null, false, false, true);
        save("Sevil Kaya", "sevil.kaya@example.com", null, true, false, true);
        save("Sevilay Demir", "sevilay.demir@example.com", OTHER_COMPANY, true, false, true);
        save("Sevim Pasif", "sevim.pasif@example.com", null, false, false, true);
        save("Sevgi Silinmiş", "sevgi.silinmis@example.com", null, true, true, true);
        save("Sevda Bağsız", "sevda.bagsiz@example.com", null, true, false, false);
        save("Yüzde Yüz", "yuzde_yuz@example.com", null, true, false, true);
        // Matches "e_y" only if '_' were a wildcard ("ece yıldız", "ece.yildiz").
        save("Ece Yıldız", "ece.yildiz@example.com", null, true, false, true);
    }

    private String save(String name, String email, Long companyId, boolean enabled, boolean deleted, boolean bound) {
        User user = new User();
        user.setName(name);
        user.setEmail(email);
        user.setPassword("x");
        user.setRole("USER");
        user.setCompanyId(companyId);
        user.setEnabled(enabled);
        String subject = UUID.randomUUID().toString();
        if (bound) {
            user.setKcSubject(subject);
        }
        if (deleted) {
            user.setDeletedAt(LocalDateTime.now());
        }
        userRepository.save(user);
        return subject;
    }

    private void grant(String... authorities) {
        when(serviceTokenVerifier.verify("service-token")).thenReturn(
                new ServiceAuthenticationToken(
                        "meeting-service",
                        "local",
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private MockHttpServletRequestBuilder search(String requester, String query, Integer limit) {
        String body = "{\"requesterSubject\":\"" + requester + "\",\"query\":\"" + query + "\""
                + (limit == null ? "" : ",\"limit\":" + limit) + "}";
        return post("/api/users/internal/assignee-candidates")
                .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("genel istekçi: yalnız genel, etkin, silinmemiş ve Keycloak'a bağlı kişiler; yalnız id+ad+e-posta")
    void globalRequesterSeesOnlyAssignableGlobalUsers() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(globalRequester, "sev", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].name").value("Sevil Kaya"))
                .andExpect(jsonPath("$.items[0].email").value("sevil.kaya@example.com"))
                .andExpect(jsonPath("$.items[0].userId").isNumber())
                // A picker that leaks profile or binding data stops being a picker.
                .andExpect(jsonPath("$.items[0].role").doesNotExist())
                .andExpect(jsonPath("$.items[0].kcSubject").doesNotExist())
                .andExpect(jsonPath("$.items[0].companyId").doesNotExist())
                .andExpect(jsonPath("$.items[0].enabled").doesNotExist());
    }

    @Test
    @DisplayName("şirketli istekçi: genel kişiler + kendi şirketi; başka şirket görünmez")
    void companyRequesterAlsoSeesOwnCompany() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(companyRequester, "SEV", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", containsInAnyOrder("Sevil Kaya", "Sevilay Demir")));
    }

    @Test
    @DisplayName("e-postanın bir parçasıyla da bulunur")
    void matchesEmail() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(globalRequester, "kaya@exa", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("Sevil Kaya")));
    }

    @Test
    @DisplayName("LIKE joker karakterleri harfiyen aranır; '%%' tüm dizini döndürmez")
    void wildcardsAreLiteral() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(globalRequester, "%%", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
        mockMvc.perform(search(globalRequester, "e_y", null))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("Yüzde Yüz")));
    }

    @Test
    @DisplayName("sınır ve sıralama: ada göre, en fazla istenen sayı kadar")
    void limitAndOrder() throws Exception {
        grant("PERM_users:internal");
        // Every seeded email contains "ex" (example.com), so all visible assignable rows match.
        mockMvc.perform(search(companyRequester, "ex", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].name", contains("Ahmet Otuzbeş", "Ece Yıldız")));
    }

    @Test
    @DisplayName("dizinde olmayan ya da pasif istekçi reddedilir")
    void unknownOrDisabledRequesterIsForbidden() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(UUID.randomUUID().toString(), "sev", null))
                .andExpect(status().isForbidden());
        mockMvc.perform(search(disabledRequester, "sev", null))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("geçersiz istek 400: kısa sorgu, büyük sınır, eksik istekçi")
    void invalidRequestsAreRejected() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(search(globalRequester, " s ", null))
                .andExpect(status().isBadRequest());
        mockMvc.perform(search(globalRequester, "sev", 21))
                .andExpect(status().isBadRequest());
        mockMvc.perform(search("", "sev", null))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("ilgisiz servis yetkisi reddedilir")
    void unrelatedServiceAuthorityIsRefused() throws Exception {
        grant("PERM_users:display-names:read");
        mockMvc.perform(search(globalRequester, "sev", null))
                .andExpect(status().isForbidden());
    }
}
