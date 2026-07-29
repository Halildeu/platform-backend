package com.example.ethics.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.ethics.config.EthicsSlaProperties;
import com.example.ethics.model.CaseWaitingReason;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Faz 35 ES-301 — a recorded wait must not move a deadline (#882).
 *
 * <p>EU 2019/1937 starts the seven days at receipt and the three months at acknowledgement
 * and provides no suspension. A wait that reduced its own duration would not be measuring the
 * obligation; it would be a way to make a breach disappear administratively, in the product
 * where that is least acceptable.
 */
class CaseWaitingReasonTest {

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    private CaseSlaClock clock() {
        return new CaseSlaClock(
                new EthicsSlaProperties(Duration.ofDays(7), Duration.ofDays(90)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /**
     * The property everything else rests on, asserted structurally rather than by example:
     * the clock's public surface takes only the case's own timestamps, so no caller — present
     * or future — can hand it a wait to subtract. Same shape as the recusal endpoint whose
     * body is empty so no one can recuse another person.
     */
    @Test
    @DisplayName("saatin imzasında bekleme süresini geçirebilecek bir parametre yok")
    void theClockHasNoParameterThroughWhichAWaitCouldReachTheArithmetic() {
        for (Method m : CaseSlaClock.class.getDeclaredMethods()) {
            if (!m.getName().equals("acknowledgement") && !m.getName().equals("feedback")) continue;
            String params = Arrays.toString(m.getParameterTypes());
            assertThat(m.getParameterCount())
                    .as("%s aldığı parametre sayısı arttı: %s", m.getName(), params)
                    .isEqualTo(2);
            assertThat(m.getParameterTypes())
                    .as("%s yalnız Instant almalı", m.getName())
                    .containsOnly(Instant.class);
        }
    }

    /** The same case, before and after a wait is recorded, produces the same deadline. */
    @Test
    @DisplayName("bekleme kaydı son tarihi oynatmaz")
    void recordingAWaitLeavesTheDeadlineWhereItWas() {
        Instant created = NOW.minus(Duration.ofDays(9));
        var before = clock().acknowledgement(created, null);

        var wait = new CaseWaitingReason(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CaseWaitingReason.AWAITING_REPORTER, NOW.minus(Duration.ofDays(5)));
        wait.end(NOW);

        var after = clock().acknowledgement(created, null);

        assertThat(after.dueAt()).isEqualTo(before.dueAt());
        assertThat(after.state()).isEqualTo(before.state());
        assertThat(after.overdueBy()).isEqualTo(before.overdueBy());
        assertThat(after.state()).isEqualTo(CaseSlaClock.AcknowledgementState.BREACHED);
    }

    /** Ending a wait twice is not an error the handler should have to read. */
    @Test
    @DisplayName("beklemeyi iki kez kapatmak ilk kapanışı korur")
    void endingAWaitTwiceKeepsTheFirstEnding() {
        var wait = new CaseWaitingReason(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CaseWaitingReason.AWAITING_REPORTER, NOW.minus(Duration.ofDays(3)));
        wait.end(NOW.minus(Duration.ofDays(1)));
        wait.end(NOW);

        assertThat(wait.getEndedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    /**
     * The vocabulary is closed in three places — the constants, the database CHECK, and the
     * service's accepted set. Today's lesson (#1012) was that a value enumerated twice moves
     * in one place and is lost in the other; here it is enumerated three times, so the guard
     * compares them.
     */
    @Test
    @DisplayName("bekleme gerekçeleri sabitler ile veritabanı kısıtında aynı")
    void theReasonVocabularyMatchesTheDatabaseConstraint() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V13__case_waiting_reason.sql"));

        for (String reason : java.util.List.of(
                CaseWaitingReason.AWAITING_REPORTER,
                CaseWaitingReason.AWAITING_EXTERNAL_AUTHORITY,
                CaseWaitingReason.AWAITING_INTERNAL_INPUT)) {
            assertThat(migration)
                    .as("%s sabitlerde var ama veritabanı kısıtında yok", reason)
                    .contains("'" + reason + "'");
        }
    }

    /**
     * Free text here would collect names — "waiting for Ahmet to answer" puts a person into a
     * column nothing sanitises. The column is length-bounded and the values are constants.
     */
    @Test
    @DisplayName("gerekçe serbest metin değil")
    void theReasonIsNotFreeText() throws Exception {
        String migration = Files.readString(
                Path.of("src/main/resources/db/migration/V13__case_waiting_reason.sql"));
        assertThat(migration).contains("ck_ethics_waiting_reason");
        assertThat(migration).doesNotContain("reason TEXT");
    }
}
