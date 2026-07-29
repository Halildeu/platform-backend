package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.config.EthicsSlaProperties;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.notification.NotificationOutboxPublisher;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-301 — the organisation is told when it has missed a legal deadline (#882).
 *
 * <p>Both existing notification events fire when a reporter acts. Nothing fired when the
 * organisation failed to act, which on the live cell meant fifty-one breached
 * acknowledgements and no message anywhere.
 */
class SlaBreachSweeperTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private EthicsCaseRepository cases;
    private NotificationOutboxRepository outbox;
    private NotificationOutboxPublisher notifications;

    @BeforeEach
    void setUp() {
        cases = mock(EthicsCaseRepository.class);
        outbox = mock(NotificationOutboxRepository.class);
        notifications = mock(NotificationOutboxPublisher.class);
        when(outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(any(), anyString(), any()))
                .thenReturn(false);
        when(cases.findDistinctOrgIds()).thenReturn(List.of(ORG));
    }

    private SlaBreachSweeper sweeper() {
        // Warning disabled: the pre-#882 behaviour, which these tests pin.
        return sweeper(0);
    }

    private SlaBreachSweeper sweeper(int warnBusinessDays) {
        var sla = new CaseSlaClock(
                new EthicsSlaProperties(Duration.ofDays(7), Duration.ofDays(90)),
                Clock.fixed(NOW, ZoneOffset.UTC));
        var calendarConfig = new com.example.ethics.config.EthicsSlaCalendarProperties(
                "Europe/Istanbul", List.of(), null, warnBusinessDays);
        return new SlaBreachSweeper(
                cases, outbox, notifications, sla,
                new BusinessCalendar(calendarConfig), calendarConfig,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private EthicsCase caseCreatedAt(Instant createdAt, Instant acknowledgedAt) {
        var item = mock(EthicsCase.class);
        when(item.getCreatedAt()).thenReturn(createdAt);
        when(item.getAcknowledgedAt()).thenReturn(acknowledgedAt);
        when(item.getClosedAt()).thenReturn(null);
        return item;
    }

    @Test
    @DisplayName("süresi geçmiş yükümlülük varsa sinyal üretilir")
    void anOverdueObligationProducesASignal() {
        var overdue = caseCreatedAt(NOW.minus(Duration.ofDays(9)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdue));

        sweeper().sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    @Test
    @DisplayName("süre içindeyken sinyal üretilmez")
    void nothingOverdueProducesNoSignal() {
        var inWindow = caseCreatedAt(NOW.minus(Duration.ofDays(3)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(inWindow));

        sweeper().sweep();

        verify(notifications, never()).enqueue(any(), anyString(), any());
    }

    /**
     * The property the whole design rests on. Fifty-one breaches must not become fifty-one
     * notifications: a channel that floods on the first bad day is a channel people mute,
     * after which the fifty-second breach is no louder than silence.
     */
    @Test
    @DisplayName("elli bir ihlal elli bir bildirim üretmez")
    void manyBreachesProduceOneSignal() {
        var many = new java.util.ArrayList<EthicsCase>();
        for (int i = 0; i < 51; i++) {
            many.add(caseCreatedAt(NOW.minus(Duration.ofDays(9 + i)), null));
        }
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(many);

        sweeper().sweep();

        verify(notifications, org.mockito.Mockito.times(1))
                .enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    @Test
    @DisplayName("aynı gün ikinci sinyal gönderilmez")
    void aSecondSignalIsSuppressedWithinTheDay() {
        var overdue = caseCreatedAt(NOW.minus(Duration.ofDays(9)), null);
        when(outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(
                eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any()))
                .thenReturn(true);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdue));

        sweeper().sweep();

        verify(notifications, never()).enqueue(any(), anyString(), any());
    }

    /**
     * Suppression must not cost a scan. Checking the outbox first is what keeps a
     * fifteen-minute sweep from reading every case in the organisation ninety-six times a
     * day for a signal it will not send.
     */
    @Test
    @DisplayName("bastırılmış turda vakalar hiç okunmaz")
    void aSuppressedSweepDoesNotReadTheCases() {
        when(outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(any(), anyString(), any()))
                .thenReturn(true);

        sweeper().sweep();

        verify(cases, never()).findAllByOrgIdOrderByUpdatedAtDesc(any());
    }

    /** Geri bildirim yükümlülüğü de tetikler; iki borç ayrı ama sinyal ortak. */
    @Test
    @DisplayName("geri bildirim süresi geçmişse de sinyal üretilir")
    void anOverdueFeedbackObligationAlsoSignals() {
        var item = mock(EthicsCase.class);
        when(item.getCreatedAt()).thenReturn(NOW.minus(Duration.ofDays(120)));
        when(item.getAcknowledgedAt()).thenReturn(NOW.minus(Duration.ofDays(119)));
        when(item.getClosedAt()).thenReturn(null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(item));

        sweeper().sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    /**
     * The event vocabulary is enumerated twice — here in Java and again in a database CHECK
     * constraint (V5, amended by V12). #1011 moved only the Java half, so the sweeper logged
     * that it had enqueued a signal and then lost it on commit:
     *
     * <pre>ERROR: new row violates check constraint "ck_ethics_notification_event"</pre>
     *
     * <p>Nothing above could see it: these tests mock the repository, so no statement ever
     * reaches Postgres. This one compares the two lists as text instead, which is the cheap
     * half of the guard — the expensive half is the constraint itself, which now fails closed
     * for a third writer who forgets the migration.
     */
    @Test
    @DisplayName("izin listesi ile veritabanı kısıtı aynı olay kümesini tanır")
    void theJavaAllowlistAndTheDatabaseConstraintAgree() throws Exception {
        var migrations = java.nio.file.Path.of("src/main/resources/db/migration");
        String constraint = java.nio.file.Files.list(migrations)
                .filter(f -> f.getFileName().toString().contains("notification"))
                .map(f -> {
                    try {
                        return java.nio.file.Files.readString(f);
                    } catch (java.io.IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .reduce("", String::concat);

        for (String event : java.util.List.of(
                "NEW_REPORT", "REPORTER_MESSAGE", "SLA_BREACH", "SLA_APPROACHING")) {
            assertThat(constraint)
                    .as("%s Java tarafinda var ama veritabani kisitinda yok", event)
                    .contains("'" + event + "'");
        }
    }

    /**
     * The gap the first version shipped with. It swept the single organisation named by
     * {@code ethics.public-org-id} — 139 cases on the live cell — and never looked at the 28
     * belonging to a second tenant, whose deadlines are exactly as legal.
     */
    @Test
    @DisplayName("her kiracı taranır, yapılandırmada adı geçen değil")
    void everyTenantWithCasesIsSwept() {
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var overdueA = caseCreatedAt(NOW.minus(Duration.ofDays(9)), null);
        var overdueB = caseCreatedAt(NOW.minus(Duration.ofDays(20)), null);
        when(cases.findDistinctOrgIds()).thenReturn(List.of(ORG, second));
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdueA));
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(second)).thenReturn(List.of(overdueB));

        sweeper().sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
        verify(notifications).enqueue(eq(second), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    /**
     * Suppression is per organisation. One tenant having already been told today must not
     * silence another tenant's first breach — that is the shape in which a multi-tenant
     * notifier quietly stops working for everyone but the loudest customer.
     */
    @Test
    @DisplayName("bir kiracının bastırılması diğerini susturmaz")
    void oneTenantsSuppressionDoesNotSilenceAnother() {
        UUID second = UUID.fromString("00000000-0000-0000-0000-000000000003");
        var overdueA = caseCreatedAt(NOW.minus(Duration.ofDays(9)), null);
        var overdueB = caseCreatedAt(NOW.minus(Duration.ofDays(20)), null);
        when(cases.findDistinctOrgIds()).thenReturn(List.of(ORG, second));
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdueA));
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(second)).thenReturn(List.of(overdueB));
        when(outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(
                eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any())).thenReturn(true);

        sweeper().sweep();

        verify(notifications, never())
                .enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
        verify(notifications).enqueue(eq(second), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    /**
     * The outbox contract: the signal names the organisation and the event type, and nothing
     * else. A case id here would put case-level facts into a transport deliberately kept
     * free of them.
     */
    @Test
    @DisplayName("sinyal vaka kimliği taşımaz")
    void theSignalCarriesNoCaseIdentifier() {
        assertThat(NotificationOutboxPublisher.class.getDeclaredMethods())
                .filteredOn(m -> m.getName().equals("enqueue"))
                .allSatisfy(m -> assertThat(m.getParameterTypes()).hasSize(3));
    }

    // ---------- ES-301 remainder: the early warning in working days (#882) ----------

    /**
     * NOW is Wednesday 2026-07-29. A case created 5 days ago has its acknowledgement due
     * Monday 2026-08-03 — three business days away, but only one working day after Friday.
     * With a 2-business-day window the warning fires now on Thursday's boundary... asserted
     * simply: due-in-3-business-days is outside window 2, due-in-1 is inside.
     */
    @Test
    @DisplayName("son tarihe iş günü penceresi kadar kalınca uyarı üretilir")
    void anApproachingDeadlineProducesAWarning() {
        // Due Friday 2026-07-31: business days from Wednesday = Thu, Fri = 2 → inside window.
        var dueSoon = caseCreatedAt(NOW.minus(Duration.ofDays(5)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(dueSoon));

        sweeper(2).sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_APPROACHING), any());
        verify(notifications, never()).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    /** The deadline itself has not moved: the same case still breaches on calendar day 7. */
    @Test
    @DisplayName("uyarı penceresi son tarihi oynatmaz — 7. takvim gününde ihlal aynı kalır")
    void theWarningWindowDoesNotMoveTheDeadline() {
        var overdue = caseCreatedAt(NOW.minus(Duration.ofDays(8)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdue));

        sweeper(2).sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
    }

    /** One piece of news per day: a breach subsumes the warning. */
    @Test
    @DisplayName("ihlal varken ayrıca uyarı üretilmez")
    void aBreachSubsumesTheWarning() {
        var overdue = caseCreatedAt(NOW.minus(Duration.ofDays(9)), null);
        var dueSoon = caseCreatedAt(NOW.minus(Duration.ofDays(5)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(overdue, dueSoon));

        sweeper(2).sweep();

        verify(notifications).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_BREACH), any());
        verify(notifications, never()).enqueue(eq(ORG), eq(NotificationOutboxPublisher.SLA_APPROACHING), any());
    }

    /** Owner-supplied means absent by default: without config the sweeper behaves as before. */
    @Test
    @DisplayName("takvim konfigürasyonu yoksa uyarı hiç üretilmez")
    void withoutCalendarConfigurationNoWarningIsEverProduced() {
        var dueSoon = caseCreatedAt(NOW.minus(Duration.ofDays(6)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(dueSoon));

        sweeper(0).sweep();

        verify(notifications, never()).enqueue(any(), eq(NotificationOutboxPublisher.SLA_APPROACHING), any());
    }

    /** An already-acknowledged obligation is done; its deadline is nobody's urgency. */
    @Test
    @DisplayName("karşılanmış yükümlülük için uyarı üretilmez")
    void aMetObligationProducesNoWarning() {
        var acknowledged = caseCreatedAt(
                NOW.minus(Duration.ofDays(5)), NOW.minus(Duration.ofDays(1)));
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(acknowledged));

        sweeper(2).sweep();

        verify(notifications, never()).enqueue(any(), eq(NotificationOutboxPublisher.SLA_APPROACHING), any());
    }

    /** The warning has its own daily suppression, independent of the breach signal's. */
    @Test
    @DisplayName("uyarı kendi günlük bastırmasına tabidir")
    void theWarningHasItsOwnDailySuppression() {
        var dueSoon = caseCreatedAt(NOW.minus(Duration.ofDays(5)), null);
        when(cases.findAllByOrgIdOrderByUpdatedAtDesc(ORG)).thenReturn(List.of(dueSoon));
        when(outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(
                eq(ORG), eq(NotificationOutboxPublisher.SLA_APPROACHING), any()))
                .thenReturn(true);

        sweeper(2).sweep();

        verify(notifications, never()).enqueue(any(), anyString(), any());
    }
}
