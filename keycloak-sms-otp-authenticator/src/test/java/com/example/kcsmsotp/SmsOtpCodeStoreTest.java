package com.example.kcsmsotp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import org.junit.jupiter.api.Test;

import com.example.kcsmsotp.SmsOtpCodeStore.Status;
import com.example.kcsmsotp.SmsOtpCodeStore.VerifyResult;

class SmsOtpCodeStoreTest {

    private static final Instant T0 = Instant.parse("2026-07-31T10:00:00Z");

    /** Mutable clock so expiry is tested by moving time, not by sleeping. */
    private static final class TestClock extends Clock {
        private Instant now = T0;

        @Override public Instant instant() { return now; }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static final class MapNotes implements SmsOtpCodeStore.Notes {
        final Map<String, String> map = new HashMap<>();

        @Override public String get(String key) { return map.get(key); }
        @Override public void set(String key, String value) { map.put(key, value); }
    }

    private final TestClock clock = new TestClock();
    private final SmsOtpCodeStore store =
            new SmsOtpCodeStore(new Random(42), clock, 300, 3, 2);
    private final MapNotes notes = new MapNotes();

    @Test
    void issue_producesSixDigitCode_andNeverStoresThePlaintext() {
        String code = store.issue(notes);

        assertThat(code).matches("\\d{6}");
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_HASH)).isNotBlank().isNotEqualTo(code);
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_SALT)).isNotBlank();
        assertThat(notes.map.values()).noneMatch(v -> v.contains(code));
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_ATTEMPTS)).isEqualTo("0");
    }

    @Test
    void verify_acceptsTheIssuedCode_once() {
        String code = store.issue(notes);

        VerifyResult first = store.verify(notes, code);
        assertThat(first.status()).isEqualTo(Status.OK);

        // Single-use: replaying the same code inside the session must fail.
        VerifyResult replay = store.verify(notes, code);
        assertThat(replay.status()).isNotEqualTo(Status.OK);
    }

    @Test
    void verify_rejectsWrongCode_andCountsDownRemainingAttempts() {
        store.issue(notes);

        VerifyResult r1 = store.verify(notes, "000001");
        assertThat(r1.status()).isEqualTo(Status.INVALID);
        assertThat(r1.remainingAttempts()).isEqualTo(2);

        VerifyResult r2 = store.verify(notes, "000002");
        assertThat(r2.status()).isEqualTo(Status.INVALID);
        assertThat(r2.remainingAttempts()).isEqualTo(1);
    }

    @Test
    void verify_thirdWrongAttemptHitsTheCeiling_andTheRealCodeStopsWorking() {
        String code = store.issue(notes);

        store.verify(notes, "111111");
        store.verify(notes, "222222");
        VerifyResult third = store.verify(notes, "333333");
        assertThat(third.status()).isEqualTo(Status.TOO_MANY_ATTEMPTS);

        // Even the correct code is dead after the ceiling — no brute-then-win.
        VerifyResult afterCeiling = store.verify(notes, code);
        assertThat(afterCeiling.status()).isEqualTo(Status.TOO_MANY_ATTEMPTS);
    }

    @Test
    void verify_afterTtl_reportsExpiredWithoutBurningAttempts() {
        String code = store.issue(notes);
        clock.advance(Duration.ofSeconds(301));

        VerifyResult result = store.verify(notes, code);
        assertThat(result.status()).isEqualTo(Status.EXPIRED);
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_ATTEMPTS)).isEqualTo("0");
    }

    @Test
    void verify_withNothingIssued_isExpiredNotCrash() {
        VerifyResult result = store.verify(notes, "123456");
        assertThat(result.status()).isEqualTo(Status.EXPIRED);
    }

    @Test
    void verify_nullAndBlankInput_areInvalidWithoutException() {
        store.issue(notes);
        assertThat(store.verify(notes, null).status()).isEqualTo(Status.INVALID);
        assertThat(store.verify(notes, "  ").status()).isEqualTo(Status.INVALID);
    }

    @Test
    void resend_replacesTheOldCode_andHitsItsOwnCeiling() {
        String first = store.issue(notes);
        assertThat(store.canResend(notes)).isTrue();

        String second = store.resend(notes);
        assertThat(store.resendCount(notes)).isEqualTo(1);
        // Deterministic under Random(42); guards the assertions below.
        assertThat(second).isNotEqualTo(first);

        // Old code is dead after a resend; only the fresh one verifies.
        assertThat(store.verify(notes, first).status()).isEqualTo(Status.INVALID);
        assertThat(store.verify(notes, second).status()).isEqualTo(Status.OK);

        store.resend(notes);
        assertThat(store.resendCount(notes)).isEqualTo(2);
        assertThat(store.canResend(notes)).isFalse();
        assertThatThrownBy(() -> store.resend(notes)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void attemptsReset_onEachIssue() {
        store.issue(notes);
        store.verify(notes, "000000");
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_ATTEMPTS)).isEqualTo("1");

        store.issue(notes);
        assertThat(notes.map.get(SmsOtpCodeStore.NOTE_ATTEMPTS)).isEqualTo("0");
    }
}
