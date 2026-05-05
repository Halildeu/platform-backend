package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class NotificationDeliveryRepositoryTest extends AbstractPostgresTest {

    @Autowired
    NotificationDeliveryRepository deliveryRepo;

    @Autowired
    NotificationIntentRepository intentRepo;

    @Test
    void persistDeliveryAndQueryByIntent() {
        NotificationIntent intent = newIntent();
        intentRepo.save(intent);

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel("email");
        delivery.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        delivery.setRecipientId("1204");
        delivery.setRecipientHash("a3f8c000000000000000000000000000");
        delivery.setProvider("smtp-test");
        delivery.setStatus(NotificationDelivery.Status.PENDING);

        NotificationDelivery saved = deliveryRepo.save(delivery);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();

        List<NotificationDelivery> results = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(results).hasSize(1);
        assertThat(results.get(0).getChannel()).isEqualTo("email");
    }

    @Test
    void retryQueryReturnsOnlyDueAttempts() {
        NotificationIntent intent = newIntent();
        intentRepo.save(intent);

        NotificationDelivery dueRetry = new NotificationDelivery();
        dueRetry.setIntentId(intent.getIntentId());
        dueRetry.setChannel("email");
        dueRetry.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        dueRetry.setRecipientHash("hash1234567890");
        dueRetry.setProvider("smtp-test");
        dueRetry.setStatus(NotificationDelivery.Status.RETRY);
        dueRetry.setNextRetryAt(OffsetDateTime.now().minusMinutes(1));
        deliveryRepo.save(dueRetry);

        NotificationDelivery futureRetry = new NotificationDelivery();
        futureRetry.setIntentId(intent.getIntentId());
        futureRetry.setChannel("email");
        futureRetry.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        futureRetry.setRecipientHash("hash9876543210");
        futureRetry.setProvider("smtp-test");
        futureRetry.setStatus(NotificationDelivery.Status.RETRY);
        futureRetry.setNextRetryAt(OffsetDateTime.now().plusHours(1));
        deliveryRepo.save(futureRetry);

        List<NotificationDelivery> dueResults = deliveryRepo.findDueForRetry(
            NotificationDelivery.Status.RETRY,
            OffsetDateTime.now(),
            PageRequest.of(0, 10)
        );

        assertThat(dueResults).extracting(NotificationDelivery::getRecipientHash)
            .contains("hash1234567890")
            .doesNotContain("hash9876543210");
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
