package com.example.ethics.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ethics.repository.NotificationOutboxRepository;
import com.example.ethics.security.StaffContext;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class NotificationDeadLetterServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final StaffContext CONTEXT = new StaffContext(ORG, "manager-subject");

    private final NotificationOutboxRepository outbox = mock(NotificationOutboxRepository.class);
    private final NotificationDeadLetterService service = new NotificationDeadLetterService(
            outbox, Clock.fixed(Instant.parse("2026-07-26T19:00:00Z"), ZoneOffset.UTC));

    @Test
    void requeueIsBoundedSoOneCallCannotSweepTheWholeBacklog() {
        when(outbox.requeueDeadLetters(eq(ORG), any(), eq(500))).thenReturn(500);
        assertThat(service.requeue(CONTEXT, 100_000)).isEqualTo(500);
    }

    @Test
    void requeueRefusesANonPositiveLimitRatherThanDoingNothingSilently() {
        when(outbox.requeueDeadLetters(eq(ORG), any(), eq(1))).thenReturn(1);
        assertThat(service.requeue(CONTEXT, 0)).isEqualTo(1);
        assertThat(service.requeue(CONTEXT, -5)).isEqualTo(1);
    }

    @Test
    void summaryReportsTheBacklogAndItsAgeSoRequeueIsNotBlind() {
        Instant oldest = Instant.parse("2026-07-24T02:00:00Z");
        Instant newest = Instant.parse("2026-07-26T16:40:00Z");
        when(outbox.deadLetterSummary(ORG)).thenReturn(new Object[] {
            23L, Timestamp.from(oldest), Timestamp.from(newest)
        });

        var summary = service.summary(CONTEXT);
        assertThat(summary.count()).isEqualTo(23L);
        assertThat(summary.oldest()).isEqualTo(oldest);
        assertThat(summary.newest()).isEqualTo(newest);
    }

    @Test
    void summaryOnAnEmptyBacklogReportsZeroRatherThanFailing() {
        when(outbox.deadLetterSummary(ORG)).thenReturn(new Object[] {null, null, null});
        var summary = service.summary(CONTEXT);
        assertThat(summary.count()).isZero();
        assertThat(summary.oldest()).isNull();
        assertThat(summary.newest()).isNull();
    }
}
