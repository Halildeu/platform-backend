package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.ProviderConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class ProviderConfigRepositoryTest extends AbstractPostgresTest {

    @Autowired
    ProviderConfigRepository repo;

    @Test
    void activeProviderResolvesCorrectVersion() {
        repo.save(provider("smtp-corporate", "email", "test", 1, false, 100));
        repo.save(provider("smtp-corporate", "email", "test", 2, true, 100));

        Optional<ProviderConfig> active = repo.findActiveByProviderAndEnv("smtp-corporate", "test");
        assertThat(active).isPresent();
        assertThat(active.get().getVersion()).isEqualTo(2);
    }

    @Test
    void priorityOrderingForChannelFailover() {
        repo.save(provider("netgsm", "sms", "test", 1, true, 10));
        repo.save(provider("iletimerkezi", "sms", "test", 1, true, 20));

        List<ProviderConfig> ordered = repo.findActiveByChannelOrderByPriority("sms", "test");
        assertThat(ordered).hasSize(2);
        assertThat(ordered.get(0).getProviderKey()).isEqualTo("netgsm");
        assertThat(ordered.get(1).getProviderKey()).isEqualTo("iletimerkezi");
    }

    private ProviderConfig provider(String key, String channel, String env,
                                     int version, boolean active, int priority) {
        ProviderConfig p = new ProviderConfig();
        p.setProviderKey(key);
        p.setChannel(channel);
        p.setEnvironment(env);
        p.setVersion(version);
        p.setActive(active);
        p.setPriority(priority);
        p.setConfig(Map.of("host", "test.example.com", "port", 587));
        p.setCredentialRef("vault://kv/platform/notify/" + key);
        return p;
    }
}
