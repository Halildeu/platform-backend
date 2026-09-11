package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.config.EthicsSlaEscalationProperties;
import com.example.ethics.config.NotificationDeliveryProperties;
import com.example.ethics.notification.NotificationOutboxPublisher;
import com.example.ethics.notification.NotificationSignalBudget;
import com.example.ethics.config.EthicsSlaProperties;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.CaseEscalation;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.CaseEscalationRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Faz 35 ES-301 — a missed deadline becomes a recorded escalation (#882).
 *
 * <p>Everything here is fixed-clock arithmetic: the sweeper is handed {@code NOW} and decides
 * from the case's own timestamps and the configured policy. No test reads wall time.
 */
class EscalationSweeperTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Duration ACK = Duration.ofDays(7);
    private static final Duration FEEDBACK = Duration.ofDays(90);

    private EthicsCaseRepository cases;
    private CaseEscalationRepository escalations;
    private AuditOutboxRepository audit;
    private NotificationOutboxPublisher notifications;
    private NotificationSignalBudget signalBudget;
    private NotificationDeliveryProperties delivery;

    @BeforeEach
    void setUp() {
        cases = mock(EthicsCaseRepository.class);
        escalations = mock(CaseEscalationRepository.class);
        audit = mock(AuditOutboxRepository.class);
        notifications = mock(NotificationOutboxPublisher.class);
        signalBudget = mock(NotificationSignalBudget.class);
        when(signalBudget.claim(any(), anyString(), any())).thenReturn(true);
        delivery = new NotificationDeliveryProperties();
        delivery.setEscalationSignalsEnabled(true);
        when(escalations.existsByCaseIdAndObligationAndLevel(any(), anyString(), anyInt())).thenReturn(false);
    }

    private EscalationSweeper sweeper(boolean enabled, Duration... steps) {
        var sla = new CaseSlaClock(new EthicsSlaProperties(ACK, FEEDBACK), Clock.fixed(NOW, ZoneOffset.UTC));
        var policy = new EthicsSlaEscalationProperties(enabled, List.of(steps));
        return new EscalationSweeper(cases, escalations, audit, sla, policy,
                TransactionOperations.withoutTransaction(), new ObjectMapper(),
                new SimpleMeterRegistry(), Clock.fixed(NOW, ZoneOffset.UTC),
                notifications, signalBudget, delivery);
    }

    /** A case the candidate query returns and the row lock re-reads. */
    private EthicsCase caseCreatedAt(Instant createdAt, Instant acknowledgedAt, Instant closedAt) {
        UUID id = UUID.randomUUID();
        var item = mock(EthicsCase.class);
        when(item.getId()).thenReturn(id);
        when(item.getOrgId()).thenReturn(ORG);
        when(item.getCreatedAt()).thenReturn(createdAt);
        when(item.getAcknowledgedAt()).thenReturn(acknowledgedAt);
        when(item.getClosedAt()).thenReturn(closedAt);
        when(cases.findWithUnmetObligations()).thenReturn(List.of(id));
        when(cases.lockById(id)).thenReturn(Optional.of(item));
        return item;
    }

    private List<CaseEscalation> savedRows() {
        var captor = ArgumentCaptor.forClass(CaseEscalation.class);
        verify(escalations, org.mockito.Mockito.atLeast(0)).save(captor.capture());
        return captor.getAllValues();
    }

    @Test
    @DisplayName("süresi geçmiş onay yükümlülüğü 1. seviyeyi kaydeder ve denetim olayı yazar")
    void aBreachedAcknowledgementRecordsLevelOne() {
        caseCreatedAt(NOW.minus(ACK).minusSeconds(1), null, null);

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.recorded()).isEqualTo(1);
        var rows = savedRows();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getObligation()).isEqualTo(CaseEscalation.ACKNOWLEDGEMENT);
        assertThat(rows.get(0).getLevel()).isEqualTo(1);
        assertThat(rows.get(0).getEscalatedAt()).isEqualTo(NOW);
        assertThat(rows.get(0).getThresholdAt()).isEqualTo(rows.get(0).getDueAt());
        verify(audit).save(any(AuditOutbox.class));
    }

    /** Strictly after: at the exact deadline nothing is late yet, so nothing is recorded. */
    @Test
    @DisplayName("son tarihin tam anında hiçbir şey kaydedilmez")
    void nothingIsRecordedAtTheExactDeadline() {
        caseCreatedAt(NOW.minus(ACK), null, null);

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.recorded()).isZero();
        verify(escalations, never()).save(any());
        verify(audit, never()).save(any());
    }

    @Test
    @DisplayName("süre içindeyken hiçbir seviye kaydedilmez")
    void nothingInsideTheWindow() {
        caseCreatedAt(NOW.minus(Duration.ofDays(3)), null, null);

        assertThat(sweeper(true, Duration.ZERO).runCycle(NOW).recorded()).isZero();
        verify(escalations, never()).save(any());
    }

    @Test
    @DisplayName("ikinci adım geçilince eksik seviyeler sırayla yazılır")
    void missingLevelsAreWrittenInOrderOncePassed() {
        // Ack due 9 days ago: level 1 (PT0S) and level 2 (P3D) both passed, level 3 (P30D) not.
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(9)), null, null);

        var result = sweeper(true, Duration.ZERO, Duration.ofDays(3), Duration.ofDays(30)).runCycle(NOW);

        assertThat(result.recorded()).isEqualTo(2);
        assertThat(savedRows()).extracting(CaseEscalation::getLevel).containsExactly(1, 2);
        assertThat(savedRows().get(1).getThresholdAt())
                .isEqualTo(savedRows().get(1).getDueAt().plus(Duration.ofDays(3)));
        verify(audit, times(2)).save(any(AuditOutbox.class));
    }

    /** Idempotent across cycles and replicas: a level already on file is not written twice. */
    @Test
    @DisplayName("kayıtlı seviye ikinci kez yazılmaz")
    void anExistingLevelIsNotWrittenAgain() {
        var item = caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(9)), null, null);
        when(escalations.existsByCaseIdAndObligationAndLevel(item.getId(), CaseEscalation.ACKNOWLEDGEMENT, 1))
                .thenReturn(true);

        var result = sweeper(true, Duration.ZERO, Duration.ofDays(3)).runCycle(NOW);

        assertThat(result.recorded()).isEqualTo(1);
        assertThat(savedRows()).extracting(CaseEscalation::getLevel).containsExactly(2);
    }

    @Test
    @DisplayName("onaylanmış vaka için onay eskalasyonu durur; geri bildirim ayrı devam eder")
    void acknowledgementStopsAcknowledgementLevelsOnly() {
        // Acknowledged late, still open, feedback window (90d) long gone.
        caseCreatedAt(NOW.minus(Duration.ofDays(120)), NOW.minus(Duration.ofDays(100)), null);

        sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(savedRows()).extracting(CaseEscalation::getObligation)
                .containsExactly(CaseEscalation.FEEDBACK);
    }

    @Test
    @DisplayName("kapalı vaka için geri bildirim eskalasyonu yazılmaz")
    void aClosedCaseRecordsNoFeedbackLevel() {
        // Closed (late) and acknowledged: nothing left to escalate.
        caseCreatedAt(NOW.minus(Duration.ofDays(120)), NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(1)));

        assertThat(sweeper(true, Duration.ZERO).runCycle(NOW).recorded()).isZero();
        verify(escalations, never()).save(any());
    }

    /**
     * The row lock decides, not the candidate scan. A case acknowledged between the two reads
     * is seen acknowledged and gets no acknowledgement level.
     */
    @Test
    @DisplayName("aday taraması ile kilit arasında onaylanan vaka seviye almaz")
    void acknowledgementCommittedBeforeTheLockWins() {
        UUID id = UUID.randomUUID();
        var locked = mock(EthicsCase.class);
        when(locked.getId()).thenReturn(id);
        when(locked.getOrgId()).thenReturn(ORG);
        when(locked.getCreatedAt()).thenReturn(NOW.minus(ACK).minusSeconds(60));
        when(locked.getAcknowledgedAt()).thenReturn(NOW.minusSeconds(1)); // acknowledged just now
        when(locked.getClosedAt()).thenReturn(null);
        when(cases.findWithUnmetObligations()).thenReturn(List.of(id)); // scan still listed it
        when(cases.lockById(id)).thenReturn(Optional.of(locked));

        assertThat(sweeper(true, Duration.ZERO).runCycle(NOW).recorded()).isZero();
        verify(escalations, never()).save(any());
    }

    @Test
    @DisplayName("politika kapalıyken runCycle da hiçbir şey yapmaz")
    void disabledIsANoOpThroughThePublicMethodToo() {
        caseCreatedAt(NOW.minus(Duration.ofDays(30)), null, null);

        var result = sweeper(false, Duration.ZERO).runCycle(NOW);

        assertThat(result.enabled()).isFalse();
        verify(cases, never()).findWithUnmetObligations();
        verify(escalations, never()).save(any());
    }

    /** The unique index is the cross-replica backstop; tripping it is "someone was first", not a fault. */
    @Test
    @DisplayName("seviye indeksindeki çakışma atlanır, hata sayılmaz")
    void aLevelIndexCollisionIsSkippedNotCounted() {
        caseCreatedAt(NOW.minus(ACK).minusSeconds(1), null, null);
        when(escalations.save(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"ux_ethics_escalation_level\"")));

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        assertThat(result.recorded()).isZero();
    }

    @Test
    @DisplayName("başka bir bütünlük hatası arıza sayılır")
    void anyOtherIntegrityFailureIsAFault() {
        caseCreatedAt(NOW.minus(ACK).minusSeconds(1), null, null);
        when(escalations.save(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("violates check constraint \"ck_ethics_escalation_level\"")));

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.skipped()).isZero();
    }

    /** One stuck case must not stall the rest. */
    @Test
    @DisplayName("bir vakanın hatası diğerlerini durdurmaz")
    void oneFailingCaseDoesNotStallTheOthers() {
        UUID broken = UUID.randomUUID();
        var fine = mock(EthicsCase.class);
        UUID fineId = UUID.randomUUID();
        when(fine.getId()).thenReturn(fineId);
        when(fine.getOrgId()).thenReturn(ORG);
        when(fine.getCreatedAt()).thenReturn(NOW.minus(ACK).minusSeconds(1));
        when(cases.findWithUnmetObligations()).thenReturn(List.of(broken, fineId));
        when(cases.lockById(broken)).thenThrow(new IllegalStateException("boom"));
        when(cases.lockById(fineId)).thenReturn(Optional.of(fine));

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.recorded()).isEqualTo(1);
    }

    /**
     * The payload is an exact allowlist, pinned as a set. No actor (the clock acted), no free
     * text, no organisation, no person — and the reviewer sees exactly which key appeared.
     */
    @Test
    @DisplayName("denetim yükü tam olarak altı alan taşır — aktör, serbest metin, kişi yok")
    void theAuditPayloadIsAnExactAllowlist() throws Exception {
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofHours(2)), null, null);
        var captor = ArgumentCaptor.forClass(AuditOutbox.class);

        sweeper(true, Duration.ZERO).runCycle(NOW);

        verify(audit).save(captor.capture());
        var row = captor.getValue();
        assertThat(row.getEventType()).isEqualTo(EscalationSweeper.EVENT_TYPE);
        assertThat(row.getOrgId()).isEqualTo(ORG);
        var payload = new ObjectMapper().readTree(row.getPayload());
        var keys = new java.util.TreeSet<String>();
        payload.fieldNames().forEachRemaining(keys::add);
        assertThat(keys).containsExactly(
                "dueAt", "escalatedAt", "level", "obligation", "overdueSeconds", "thresholdAt");
        assertThat(payload.get("obligation").asText()).isEqualTo(CaseEscalation.ACKNOWLEDGEMENT);
        assertThat(payload.get("level").asInt()).isEqualTo(1);
        assertThat(payload.get("overdueSeconds").asLong()).isEqualTo(Duration.ofHours(2).getSeconds());
        assertThat(payload.get("escalatedAt").asText()).isEqualTo(NOW.toString());
    }

    /**
     * A database error's text can quote the failing row — case id, organisation, timestamps.
     * Every failure path logs a class name and counts, never the message or the trace.
     */
    @Test
    @DisplayName("hiçbir hata yolu exception metnini loglamaz")
    void noFailurePathLogsTheExceptionText() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EscalationSweeper.class);
        var captured = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        captured.start();
        logger.addAppender(captured);
        try {
            caseCreatedAt(NOW.minus(ACK).minusSeconds(1), null, null);
            when(escalations.save(any())).thenThrow(new DataIntegrityViolationException(
                    "could not execute statement",
                    new RuntimeException("violates check constraint; Detail: Failing row contains (PII_SENTINEL_A)")));
            sweeper(true, Duration.ZERO).runCycle(NOW);

            // doThrow: `when(save(...))` would invoke the stub above and throw here.
            org.mockito.Mockito.doThrow(new IllegalStateException("boom PII_SENTINEL_B"))
                    .when(escalations).save(any());
            sweeper(true, Duration.ZERO).runCycle(NOW);

            assertThat(captured.list).isNotEmpty();
            for (var event : captured.list) {
                String line = event.getFormattedMessage();
                assertThat(line).doesNotContain("PII_SENTINEL");
                assertThat(event.getThrowableProxy()).as("stack trace logged: " + line).isNull();
            }
        } finally {
            logger.detachAppender(captured);
        }
    }

    /** Microseconds, like the column: a nanosecond past the deadline is not "after" it. */
    @Test
    @DisplayName("son tarihten bir nanosaniye sonrası 'sonra' sayılmaz; bir mikrosaniye sayılır")
    void aNanosecondPastTheDeadlineIsNotAfterItButAMicrosecondIs() {
        caseCreatedAt(NOW.minus(ACK), null, null); // due exactly at NOW

        assertThat(sweeper(true, Duration.ZERO).runCycle(NOW.plusNanos(1)).recorded()).isZero();
        assertThat(sweeper(true, Duration.ZERO).runCycle(NOW.plusNanos(1_000)).recorded()).isEqualTo(1);
        assertThat(savedRows().get(0).getEscalatedAt()).isEqualTo(NOW.plusNanos(1_000));
    }

    /** NOWAIT: a row someone else holds is deferred to the next cycle, not waited for or failed. */
    @Test
    @DisplayName("başkasının tuttuğu satır ertelenir, hata sayılmaz")
    void aHeldRowIsDeferredNotFailed() {
        UUID id = UUID.randomUUID();
        when(cases.findWithUnmetObligations()).thenReturn(List.of(id));
        when(cases.lockById(id)).thenThrow(new org.springframework.dao.CannotAcquireLockException("held"));

        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.deferred()).isEqualTo(1);
        assertThat(result.failed()).isZero();
        verify(escalations, never()).save(any());
    }

    /** The meter says what was committed. A level rolled back with its transaction was not. */
    @Test
    @DisplayName("geri alınan seviye 'recorded' metriğini artırmaz")
    void aRolledBackLevelDoesNotCountAsRecorded() {
        caseCreatedAt(NOW.minus(ACK).minusSeconds(1), null, null);
        when(audit.save(any())).thenThrow(new IllegalStateException("ledger unavailable"));
        var metrics = new SimpleMeterRegistry();
        var sla = new CaseSlaClock(new EthicsSlaProperties(ACK, FEEDBACK), Clock.fixed(NOW, ZoneOffset.UTC));
        var sweeper = new EscalationSweeper(cases, escalations, audit, sla,
                new EthicsSlaEscalationProperties(true, List.of(Duration.ZERO)),
                TransactionOperations.withoutTransaction(), new ObjectMapper(), metrics,
                Clock.fixed(NOW, ZoneOffset.UTC), notifications, signalBudget, delivery);

        var result = sweeper.runCycle(NOW);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.recorded()).isZero();
        assertThat(metrics.get("ethics.sla.escalation.recorded").counter().count()).isZero();
        assertThat(metrics.get("ethics.sla.escalation.failed").counter().count()).isEqualTo(1.0);
    }

    /** The public surface of the policy stays free of anything a pause could reach. */
    @Test
    @DisplayName("politika hesabı yalnız iki an alır — bekleme nedeni ona ulaşamaz")
    void thePolicyArithmeticTakesOnlyTwoInstants() {
        var method = java.util.Arrays.stream(EthicsSlaEscalationProperties.class.getDeclaredMethods())
                .filter(m -> m.getName().equals("levelReached"))
                .findFirst().orElseThrow();
        assertThat(method.getParameterTypes()).containsExactly(Instant.class, Instant.class);
        assertThat(method.getParameterTypes()).doesNotContain(Duration.class);
    }

    // ── ES-301b (#1153): the notification for each recorded level ─────────────────────

    @Test
    @DisplayName("her kaydedilen seviye kendi olayıyla bir bildirim sinyali üretir")
    void everyRecordedLevelSignalsItsOwnEvent() {
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(4)), null, null);
        sweeper(true, Duration.ZERO, Duration.ofDays(3)).runCycle(NOW);

        verify(notifications).enqueue(ORG, "CASE_ESCALATED_L1", NOW);
        verify(notifications).enqueue(ORG, "CASE_ESCALATED_L2", NOW);
        verify(signalBudget).claim(ORG, "CASE_ESCALATED_L1", NOW);
        verify(signalBudget).claim(ORG, "CASE_ESCALATED_L2", NOW);
    }

    @Test
    @DisplayName("bütçe verilmezse (aynı kurum, aynı seviye, aynı gün) sinyal üretilmez; seviye yine kaydedilir")
    void aSpentBudgetSuppressesTheSignalButNotTheLevel() {
        when(signalBudget.claim(any(), anyString(), any())).thenReturn(false);
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(1)), null, null);
        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.recorded()).isEqualTo(1);
        verify(escalations).save(any());
        verify(notifications, never()).enqueue(any(), anyString(), any());
    }

    @Test
    @DisplayName("rollout bayrağı kapalıyken seviye kaydedilir, bildirim üretilmez, bütçeye dokunulmaz")
    void theRolloutFlagOffRecordsLevelsSilently() {
        delivery.setEscalationSignalsEnabled(false);
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(1)), null, null);
        var result = sweeper(true, Duration.ZERO).runCycle(NOW);

        assertThat(result.recorded()).isEqualTo(1);
        verify(notifications, never()).enqueue(any(), anyString(), any());
        verify(signalBudget, never()).claim(any(), anyString(), any());
    }

    @Test
    @DisplayName("daha önce kaydedilmiş bir seviye için yeniden sinyal üretilmez")
    void anAlreadyRecordedLevelDoesNotSignalAgain() {
        when(escalations.existsByCaseIdAndObligationAndLevel(any(), anyString(), anyInt())).thenReturn(true);
        caseCreatedAt(NOW.minus(ACK).minus(Duration.ofDays(1)), null, null);
        sweeper(true, Duration.ZERO).runCycle(NOW);

        verify(notifications, never()).enqueue(any(), anyString(), any());
    }
}
