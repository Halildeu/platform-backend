package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.IdempotencyKey;
import com.serban.notify.domain.NotificationIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class IdempotencyKeyRepositoryTest extends AbstractPostgresTest {

    @Autowired
    IdempotencyKeyRepository idemRepo;

    @Autowired
    NotificationIntentRepository intentRepo;

    @Test
    void activeKeyLookupRespectsExpiresAt() {
        NotificationIntent intent = newIntent();
        intentRepo.save(intent);

        IdempotencyKey active = new IdempotencyKey();
        active.setOrgId("default");
        active.setIdempotencyKey("test-key-active");
        active.setIntentId(intent.getIntentId());
        active.setExpiresAt(OffsetDateTime.now().plusHours(24));
        idemRepo.save(active);

        Optional<IdempotencyKey> found = idemRepo.findActiveKey(
            "default", "test-key-active", OffsetDateTime.now()
        );

        assertThat(found).isPresent();
        assertThat(found.get().getIntentId()).isEqualTo(intent.getIntentId());
    }

    @Test
    void expiredKeyNotReturnedAsActive() {
        NotificationIntent intent = newIntent();
        intentRepo.save(intent);

        IdempotencyKey expired = new IdempotencyKey();
        expired.setOrgId("default");
        expired.setIdempotencyKey("test-key-expired");
        expired.setIntentId(intent.getIntentId());
        expired.setExpiresAt(OffsetDateTime.now().minusHours(1));
        idemRepo.save(expired);

        Optional<IdempotencyKey> found = idemRepo.findActiveKey(
            "default", "test-key-expired", OffsetDateTime.now()
        );

        assertThat(found).isEmpty();
    }

    private NotificationIntent newIntent() {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setOrgId("default");
        intent.setTopicKey("test.topic");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("k", "v"));
        intent.setTemplateId("test-template");
        intent.setLocale("tr-TR");
        intent.setChannels(new String[]{"email"});
        return intent;
    }
}
