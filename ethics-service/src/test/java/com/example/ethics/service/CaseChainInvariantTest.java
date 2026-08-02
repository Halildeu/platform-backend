package com.example.ethics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.model.CaseSanction;
import com.example.ethics.model.CaseSanction.Band;
import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.RetaliationCheckRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ES-213 (#3375) — the rules the last two links of the case chain are judged on.
 *
 * <p>Both come from documents rather than from taste: the severity bands are Açık
 * Holding's İHLAL AĞIRLIK CETVELİ, and the monitoring schedule and vocabulary are MDL35
 * and Directive 2019/1937 art. 19. Where a rule is written down elsewhere, breaking it in
 * code is a silent divergence from the policy the organisation published, so each is
 * pinned here.
 */
class CaseChainInvariantTest {

    private static final UUID CASE = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final Instant CLOSED = Instant.parse("2026-08-02T00:00:00Z");

    // ---- severity scale ----------------------------------------------------

    @Test
    void bandsFollowTheScaleRatherThanTheAuthor() {
        assertEquals(Band.HAFIF, Band.ofScore(1));
        assertEquals(Band.HAFIF, Band.ofScore(10));
        assertEquals(Band.ORTA, Band.ofScore(11));
        assertEquals(Band.ORTA, Band.ofScore(20));
        assertEquals(Band.AGIR, Band.ofScore(21));
        assertEquals(Band.AGIR, Band.ofScore(30));
        assertEquals(Band.COK_AGIR, Band.ofScore(31));
        assertEquals(Band.COK_AGIR, Band.ofScore(40));
        assertThrows(IllegalArgumentException.class, () -> Band.ofScore(0));
        assertThrows(IllegalArgumentException.class, () -> Band.ofScore(41));
    }

    @Test
    void aBandBelowTheScoreIsRefused() {
        // Reading the scale downwards is how a serious finding quietly becomes a warning.
        assertThrows(IllegalArgumentException.class, () -> new CaseSanction(
                UUID.randomUUID(), CASE, ORG, 28, Band.ORTA, null, "WRITTEN_WARNING", "h", CLOSED));
    }

    @Test
    void escalatingAboveTheScoreRequiresAReason() {
        // The scale's automatic-escalation list exists precisely so a single bribe or one
        // act of harassment does not band as HAFİF on a low total. Escalation is allowed —
        // it just cannot be silent.
        assertThrows(IllegalArgumentException.class, () -> new CaseSanction(
                UUID.randomUUID(), CASE, ORG, 8, Band.COK_AGIR, null, "TERMINATION", "h", CLOSED));

        CaseSanction escalated = new CaseSanction(UUID.randomUUID(), CASE, ORG, 8, Band.COK_AGIR,
                "PUBLIC_OFFICIAL_BRIBERY — cetvel otomatik yükseltme listesi", "TERMINATION", "h", CLOSED);
        assertEquals("COK_AGIR", escalated.getSeverityBand());
        assertTrue(CaseSanction.AUTOMATIC_ESCALATIONS.contains("PUBLIC_OFFICIAL_BRIBERY"));
    }

    @Test
    void applyingRequiresVerificationAndAnOverturnedSanctionCannotBeApplied() {
        CaseSanction s = new CaseSanction(UUID.randomUUID(), CASE, ORG, 25, Band.AGIR, null,
                "SUSPENSION", "h", CLOSED);
        assertThrows(IllegalArgumentException.class, () -> s.markApplied("h2", "  ", CLOSED));

        s.moveAppeal("REQUESTED");
        s.moveAppeal("OVERTURNED");
        assertThrows(IllegalStateException.class,
                () -> s.markApplied("h2", "İK dosyasında imzalı tebliğ", CLOSED));
    }

    @Test
    void appealsMoveForwardOnly() {
        CaseSanction s = new CaseSanction(UUID.randomUUID(), CASE, ORG, 15, Band.ORTA, null,
                "WRITTEN_WARNING", "h", CLOSED);
        assertThrows(IllegalStateException.class, () -> s.moveAppeal("UPHELD"));
        s.moveAppeal("REQUESTED");
        s.moveAppeal("UPHELD");
        // Re-litigating a concluded appeal until it comes out the desired way is the
        // outcome an appeal process exists to prevent.
        assertThrows(IllegalStateException.class, () -> s.moveAppeal("OVERTURNED"));
        assertThrows(IllegalStateException.class, () -> s.moveAppeal("REQUESTED"));
    }

    // ---- retaliation monitoring -------------------------------------------

    private RetaliationMonitoringService serviceWith(List<RetaliationCheck> store,
                                                     RetaliationCheckRepository repo) {
        when(repo.save(any(RetaliationCheck.class))).thenAnswer(inv -> {
            store.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findAllByCaseIdOrderByPeriodMonthsAsc(CASE)).thenReturn(store);
        return new RetaliationMonitoringService(repo);
    }

    @Test
    void closingACaseOpensThreeChecksAtThreeSixAndTwelveMonths() {
        RetaliationCheckRepository repo = mock(RetaliationCheckRepository.class);
        when(repo.existsByCaseId(CASE)).thenReturn(false);
        List<RetaliationCheck> store = new ArrayList<>();
        serviceWith(store, repo).openScheduleFor(CASE, ORG, CLOSED);

        assertEquals(3, store.size());
        assertEquals(List.of((short) 3, (short) 6, (short) 12),
                store.stream().map(RetaliationCheck::getPeriodMonths).toList());
        // Dated from the case's own closure, not from "now": a case closed in a backfill
        // still owes its reporter three, six and twelve months from the day it ended.
        assertEquals(CLOSED.atZone(ZoneOffset.UTC).plusMonths(3).toInstant(), store.get(0).getDueAt());
        assertEquals(CLOSED.atZone(ZoneOffset.UTC).plusMonths(12).toInstant(), store.get(2).getDueAt());
    }

    @Test
    void closingTwiceDoesNotDoubleTheSchedule() {
        RetaliationCheckRepository repo = mock(RetaliationCheckRepository.class);
        when(repo.existsByCaseId(CASE)).thenReturn(true);
        when(repo.findAllByCaseIdOrderByPeriodMonthsAsc(CASE)).thenReturn(List.of());
        new RetaliationMonitoringService(repo).openScheduleFor(CASE, ORG, CLOSED);
        // Six checks would make the backlog look worse than it is and quietly inflate every
        // "outstanding monitoring" number the programme reports.
        verify(repo, never()).save(any());
    }

    @Test
    void aConcludedCheckMustCarryAnObservationAndAJudgement() {
        RetaliationCheck c = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 3,
                CLOSED.plus(90, ChronoUnit.DAYS));
        assertThrows(IllegalArgumentException.class,
                () -> c.conclude("  ", "NONE", null, Set.of(), "h", CLOSED));
        assertThrows(IllegalArgumentException.class,
                () -> c.conclude("görüşüldü", null, null, Set.of(), "h", CLOSED));
    }

    @Test
    void suspectedRetaliationWithoutAnActionIsRefused() {
        RetaliationCheck c = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 6,
                CLOSED.plus(180, ChronoUnit.DAYS));
        // Noticing is not protecting. The directive's duty does not end at the observation.
        assertThrows(IllegalArgumentException.class, () -> c.conclude(
                "Görev değişikliği bildirildi", "SUSPECTED", null, Set.of("DUTY_TRANSFER"), "h", CLOSED));
    }

    @Test
    void namingARetaliationFormWhileJudgingItHarmlessIsRefused() {
        RetaliationCheck c = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 12,
                CLOSED.plus(365, ChronoUnit.DAYS));
        // This is the shape a programme takes when someone wants the checkbox without the
        // consequence: a documented demotion filed under a clean bill of health.
        assertThrows(IllegalArgumentException.class, () -> c.conclude(
                "Rütbe indirimi yapıldı", "NONE", null, Set.of("DEMOTION"), "h", CLOSED));

        c.conclude("Rütbe indirimi yapıldı", "CONFIRMED", "İK kararı geri alındı; kurul bilgilendirildi",
                Set.of("DEMOTION"), "h", CLOSED);
        assertEquals("CONFIRMED", c.getRisk());
    }

    @Test
    void askedIsDistinctFromDueAndIsStampedOnlyOnce() {
        RetaliationCheck c = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 3,
                CLOSED.plus(90, ChronoUnit.DAYS));
        assertEquals(null, c.getAskedAt());
        c.markAsked(CLOSED.plus(95, ChronoUnit.DAYS));
        c.markAsked(CLOSED.plus(200, ChronoUnit.DAYS));
        // The gap between due and asked is the only honest measure of whether this is being
        // run; letting a later call overwrite it would erase five days of lateness.
        assertEquals(CLOSED.plus(95, ChronoUnit.DAYS), c.getAskedAt());
    }
}
