package com.serban.notify.inbox;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.repository.NotificationInboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * InboxNotifyListener Testcontainers integration test (Faz 23.4 PR-E.4
 * Codex iter-1 P1.4 absorb).
 *
 * <p>Real PG LISTEN/NOTIFY round-trip:
 * <ul>
 *   <li>Cross-pod-enabled=true (production default; overrides test profile false)</li>
 *   <li>Publisher emits pg_notify via JdbcTemplate</li>
 *   <li>Listener background thread receives, parses, recomputes count, emits Spring event</li>
 *   <li>Test {@link RecordingEventListener} captures events for assertion</li>
 * </ul>
 *
 * <p>Awaitility used for async event arrival (PG LISTEN poll cadence ~1s).
 *
 * <p>Test-only TestConfig overrides cross-pod-enabled=true so the listener
 * actually starts (default test profile disables it).
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "notify.inbox.cross-pod-enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class InboxNotifyListenerIntegrationTest extends AbstractPostgresTest {

    @Autowired InboxEventPublisher publisher;
    @Autowired NotificationInboxRepository inboxRepository;
    @Autowired RecordingEventListener recorder;

    @BeforeEach
    void cleanInbox() {
        inboxRepository.deleteAll();
        recorder.clear();
    }

    @AfterEach
    void clearRecorder() {
        recorder.clear();
    }

    @Test
    @Transactional
    void publishViaPgNotifyDeliversToListenerAndRecomputesFreshCount() {
        // Seed 3 UNREAD inbox rows for sub-x in default org
        for (int i = 0; i < 3; i++) {
            NotificationInbox row = new NotificationInbox();
            row.setOrgId("default");
            row.setIntentId("intent-" + i);
            row.setSubscriberId("sub-x");
            row.setLocale("tr-TR");
            row.setTopicKey("test.topic");
            row.setSeverity("info");
            row.setState(NotificationInbox.State.UNREAD);
            row.setCreatedAt(OffsetDateTime.now());
            inboxRepository.save(row);
        }
        inboxRepository.flush();

        // Trigger NOTIFY (cross-pod path); listener should receive + recompute → 3
        publisher.publishInboxUpdated("default", "sub-x");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(recorder.events).anyMatch(ev ->
                "default".equals(ev.orgId())
                    && "sub-x".equals(ev.subscriberId())
                    && ev.unreadCount() == 3L
            );
        });
    }

    @Test
    @Transactional
    void zeroUnreadDeliveredEvenIfNoMatchingRows() {
        // No inbox rows for sub-y; publisher still NOTIFYs, listener recomputes 0
        publisher.publishInboxUpdated("default", "sub-y");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(recorder.events).anyMatch(ev ->
                "default".equals(ev.orgId())
                    && "sub-y".equals(ev.subscriberId())
                    && ev.unreadCount() == 0L
            );
        });
    }

    /** Test bean — captures InboxUpdatedEvents emitted by the LISTEN worker. */
    @Component
    static class RecordingEventListener {
        final java.util.List<InboxUpdatedEvent> events = new CopyOnWriteArrayList<>();

        @EventListener
        public void capture(InboxUpdatedEvent event) {
            events.add(event);
        }

        void clear() {
            events.clear();
        }
    }
}
