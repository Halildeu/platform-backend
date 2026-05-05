package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.SubscriberPreference;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class SubscriberPreferenceRepositoryTest extends AbstractPostgresTest {

    @Autowired
    SubscriberPreferenceRepository repo;

    @Test
    void persistAndLookupBySubscriber() {
        SubscriberPreference p1 = new SubscriberPreference();
        p1.setSubscriberId("1204");
        p1.setOrgId("default");
        p1.setTopicKey("auth.password-reset");
        p1.setChannel("email");
        p1.setEnabled(true);
        repo.save(p1);

        SubscriberPreference p2 = new SubscriberPreference();
        p2.setSubscriberId("1204");
        p2.setOrgId("default");
        p2.setTopicKey("system.update");
        p2.setChannel("email");
        p2.setEnabled(false);
        repo.save(p2);

        List<SubscriberPreference> prefs = repo.findBySubscriberIdAndOrgId("1204", "default");
        assertThat(prefs).hasSize(2);

        SubscriberPreference resetPref = prefs.stream()
            .filter(p -> "auth.password-reset".equals(p.getTopicKey()))
            .findFirst()
            .orElseThrow();
        assertThat(resetPref.isEnabled()).isTrue();
        assertThat(resetPref.isBypassForCritical()).isTrue();

        SubscriberPreference updatePref = prefs.stream()
            .filter(p -> "system.update".equals(p.getTopicKey()))
            .findFirst()
            .orElseThrow();
        assertThat(updatePref.isEnabled()).isFalse();
    }
}
