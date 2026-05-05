package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class NotificationTemplateRepositoryTest extends AbstractPostgresTest {

    @Autowired
    NotificationTemplateRepository repo;

    @Test
    void findActiveByTemplateIdAndLocaleReturnsCorrectVersion() {
        NotificationTemplate v1 = template("auth.password-reset", 1, "tr-TR", true);
        NotificationTemplate v2 = template("auth.password-reset", 2, "tr-TR", true);
        repo.saveAll(java.util.List.of(v1, v2));

        Optional<NotificationTemplate> active = repo.findActiveByTemplateIdAndLocale(
            "auth.password-reset", "tr-TR"
        );

        assertThat(active).isPresent();
        assertThat(active.get().getVersion()).isEqualTo(2);
    }

    @Test
    void localeFallbackHonored() {
        NotificationTemplate trTR = template("auth.invite", 1, "tr-TR", true);
        NotificationTemplate enUS = template("auth.invite", 1, "en-US", true);
        repo.saveAll(java.util.List.of(trTR, enUS));

        assertThat(repo.findActiveByTemplateIdAndLocale("auth.invite", "tr-TR")).isPresent();
        assertThat(repo.findActiveByTemplateIdAndLocale("auth.invite", "en-US")).isPresent();
        assertThat(repo.findActiveByTemplateIdAndLocale("auth.invite", "fr-FR")).isEmpty();
    }

    private NotificationTemplate template(String id, int version, String locale, boolean active) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId(id);
        t.setVersion(version);
        t.setLocale(locale);
        t.setSubject("Test subject v" + version);
        t.setBodyHtml("<p>Hello ${user_name}</p>");
        t.setBodyText("Hello ${user_name}");
        t.setActive(active);
        t.setCreatedBy("test");
        return t;
    }
}
