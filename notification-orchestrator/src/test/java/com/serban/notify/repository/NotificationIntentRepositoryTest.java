package com.serban.notify.repository;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@Transactional
class NotificationIntentRepositoryTest extends AbstractPostgresTest {

    @Autowired
    NotificationIntentRepository repo;

    @Test
    void persistAndRetrieveIntent() {
        NotificationIntent intent = newIntent();
        NotificationIntent saved = repo.save(intent);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(NotificationIntent.Status.PENDING);

        Optional<NotificationIntent> found = repo.findByIntentId(intent.getIntentId());
        assertThat(found).isPresent();
        assertThat(found.get().getOrgId()).isEqualTo("default");
        assertThat(found.get().getPayload()).containsKey("user_name");
    }

    @Test
    void findDueForProcessingReturnsPendingScheduled() {
        NotificationIntent due = newIntent();
        due.setIntentId(UUID.randomUUID().toString());
        due.setScheduledAt(OffsetDateTime.now().minusMinutes(1));
        repo.save(due);

        NotificationIntent future = newIntent();
        future.setIntentId(UUID.randomUUID().toString());
        future.setScheduledAt(OffsetDateTime.now().plusHours(1));
        repo.save(future);

        // The container is shared with other test classes. Verify eligibility,
        // not whether our row happens to occur in an unordered first page.
        // The test transaction also keeps background workers from consuming
        // these uncommitted fixtures and rolls them back after each test.
        List<NotificationIntent> result = repo.findDueForProcessing(
            NotificationIntent.Status.PENDING,
            OffsetDateTime.now(),
            Pageable.unpaged()
        );

        assertThat(result).extracting(NotificationIntent::getIntentId)
            .contains(due.getIntentId())
            .doesNotContain(future.getIntentId());
    }

    private NotificationIntent newIntent() {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setOrgId("default");
        intent.setTopicKey("test.topic");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("user_name", "Halil", "test", true));
        intent.setTemplateId("test-template");
        intent.setLocale("tr-TR");
        intent.setChannels(new String[]{"email"});
        return intent;
    }
}
