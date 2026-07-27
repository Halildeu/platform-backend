package com.example.user.controller;

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

/**
 * Faz 35 ES-203/C — the display-name resolver.
 *
 * <p>Four properties. The narrow permission is enough (its dedicated caller
 * never holds {@code users:internal}); a deleted subject answers exactly like
 * an unknown one (no existence oracle for erased identities); the answer
 * carries a name and nothing else; and the order of the answer is the order
 * of the question.
 */
@SpringBootTest(classes = UserApplication.class, webEnvironment = WebEnvironment.MOCK)
@AutoConfigureMockMvc
@Import(TestSecurityConfig.class)
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb-displaynames;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.flyway.enabled=false",
        "spring.main.allow-bean-definition-overriding=true"
})
class DisplayNamesInternalEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private ServiceTokenVerifier serviceTokenVerifier;

    private String activeSubject;
    private String deletedSubject;

    @BeforeEach
    void seed() {
        userRepository.deleteAll();
        activeSubject = UUID.randomUUID().toString();
        deletedSubject = UUID.randomUUID().toString();

        User active = new User();
        active.setEmail("ayse.yilmaz@example.com");
        active.setName("Ayşe Yılmaz");
        active.setPassword("x");
        active.setEnabled(true);
        active.setRole("USER");
        active.setKcSubject(activeSubject);
        userRepository.save(active);

        User deleted = new User();
        deleted.setEmail("silinmis@example.com");
        deleted.setName("Silinmiş Kişi");
        deleted.setPassword("x");
        deleted.setEnabled(false);
        deleted.setRole("USER");
        deleted.setKcSubject(deletedSubject);
        deleted.setDeletedAt(LocalDateTime.now());
        userRepository.save(deleted);
    }

    private void grant(String... authorities) {
        when(serviceTokenVerifier.verify("service-token")).thenReturn(
                new ServiceAuthenticationToken(
                        "ethics-service",
                        "local",
                        List.of(authorities).stream().map(SimpleGrantedAuthority::new).toList()));
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder lookup(String body) {
        return post("/api/users/internal/display-names")
                .header(HttpHeaders.AUTHORIZATION, "Bearer service-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    @Test
    @DisplayName("dar izin yeter — sıra korunur, silinmiş ve bilinmeyen aynı sessizlikle döner")
    void narrowPermissionResolvesAndPreservesOrder() throws Exception {
        grant("PERM_users:display-names:read");
        String unknown = UUID.randomUUID().toString();
        String body = "{\"subjects\":[\"" + unknown + "\",\"" + activeSubject + "\",\"" + deletedSubject + "\"]}";

        mockMvc.perform(lookup(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(3)))
                .andExpect(jsonPath("$[0].subject").value(unknown))
                .andExpect(jsonPath("$[0].displayName").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$[1].subject").value(activeSubject))
                .andExpect(jsonPath("$[1].displayName").value("Ayşe Yılmaz"))
                // Deleted answers exactly like unknown: the resolver must not be an
                // existence oracle for erased identities.
                .andExpect(jsonPath("$[2].displayName").value(org.hamcrest.Matchers.nullValue()))
                // A name resolver that leaks profile data stops being a name resolver.
                .andExpect(jsonPath("$[1].email").doesNotExist())
                .andExpect(jsonPath("$[1].role").doesNotExist())
                .andExpect(jsonPath("$[1].id").doesNotExist());
    }

    @Test
    @DisplayName("geniş users:internal izni de kabul edilir (geniş dar'ı kapsar)")
    void broadInternalPermissionAlsoAccepted() throws Exception {
        grant("PERM_users:internal");
        mockMvc.perform(lookup("{\"subjects\":[\"" + activeSubject + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].displayName").value("Ayşe Yılmaz"));
    }

    @Test
    @DisplayName("ilgisiz servis yetkisi reddedilir")
    void unrelatedServiceAuthorityIsRefused() throws Exception {
        grant("PERM_notify:intents:system");
        mockMvc.perform(lookup("{\"subjects\":[\"" + activeSubject + "\"]}"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("parti sınırı: 200'den fazla subject 400 döner")
    void batchOverLimitIsRefused() throws Exception {
        grant("PERM_users:display-names:read");
        StringBuilder body = new StringBuilder("{\"subjects\":[");
        for (int i = 0; i < 201; i++) {
            if (i > 0) body.append(',');
            body.append('"').append(UUID.randomUUID()).append('"');
        }
        body.append("]}");
        mockMvc.perform(lookup(body.toString()))
                .andExpect(status().isBadRequest());
    }
}
