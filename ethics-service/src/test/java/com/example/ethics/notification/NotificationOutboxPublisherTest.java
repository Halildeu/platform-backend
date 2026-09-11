package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Instant;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** ES-301b (#1153): the escalation vocabulary is exactly five levels, nothing looser. */
class NotificationOutboxPublisherTest {

    @Test
    @DisplayName("seviye 1..5 olay adına, 0 ve 6 reddedilir")
    void escalationEventsCoverExactlyTheReachableLevels() {
        assertThat(NotificationOutboxPublisher.escalation(1)).isEqualTo("CASE_ESCALATED_L1");
        assertThat(NotificationOutboxPublisher.escalation(5)).isEqualTo("CASE_ESCALATED_L5");
        assertThatThrownBy(() -> NotificationOutboxPublisher.escalation(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> NotificationOutboxPublisher.escalation(6)).isInstanceOf(IllegalArgumentException.class);
        assertThat(NotificationOutboxPublisher.allowed()).containsExactlyInAnyOrder(
                "NEW_REPORT", "REPORTER_MESSAGE", "SLA_BREACH", "SLA_APPROACHING",
                "CASE_ESCALATED_L1", "CASE_ESCALATED_L2", "CASE_ESCALATED_L3",
                "CASE_ESCALATED_L4", "CASE_ESCALATED_L5");
    }

    @Test
    @DisplayName("olay adından seviye okunur; bozuk son ekler boş döner")
    void levelParsingRejectsMalformedSuffixes() {
        assertThat(NotificationOutboxPublisher.escalationLevel("CASE_ESCALATED_L2")).isEqualTo(OptionalInt.of(2));
        for (String bad : new String[] {"CASE_ESCALATED_L0", "CASE_ESCALATED_L6", "CASE_ESCALATED_L10",
                "CASE_ESCALATED_LX", "CASE_ESCALATED_L", "SLA_BREACH", "", null}) {
            assertThat(NotificationOutboxPublisher.escalationLevel(bad)).as(String.valueOf(bad)).isEmpty();
        }
    }

    @Test
    @DisplayName("izin listesi dışındaki olay kuyruğa yazılmaz")
    void anUnknownEventIsRefusedBeforeTheDatabase() {
        var outbox = mock(NotificationOutboxRepository.class);
        var publisher = new NotificationOutboxPublisher(outbox);
        assertThatThrownBy(() -> publisher.enqueue(UUID.randomUUID(), "CASE_ESCALATED_L6", Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class);
        verify(outbox, never()).save(any());
        publisher.enqueue(UUID.randomUUID(), "CASE_ESCALATED_L5", Instant.EPOCH);
        verify(outbox).save(any());
    }
}
