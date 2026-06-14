package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.3B gate-4 — {@link TpmNonceStore} V1/T-1 behaviour: single-use,
 * TTL expiry, scope binding (and scope-mismatch must NOT burn the entry).
 */
class TpmNonceStoreTest {

    /** Adjustable test clock (Clock is immutable; this lets tests advance time). */
    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant start) { this.now = start; }
        void advance(Duration d) { now = now.plus(d); }
        @Override public Instant instant() { return now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }

    private static final Instant T0 = Instant.parse("2026-06-14T00:00:00Z");
    private final byte[] nonce = "nonce-bytes".getBytes();
    private final byte[] secret = "server-secret-bytes".getBytes();

    @Test
    void consume_returnsBoundValuesExactlyOnce() {
        MutableClock clock = new MutableClock(T0);
        InMemoryTpmNonceStore store = new InMemoryTpmNonceStore(clock);
        store.issue("n1", "tok|tenant|dev", nonce, secret, T0.plusSeconds(300));

        Optional<TpmNonceStore.Consumed> first = store.consume("n1", "tok|tenant|dev");
        assertThat(first).isPresent();
        assertThat(first.get().nonce()).isEqualTo(nonce);
        assertThat(first.get().serverSecret()).isEqualTo(secret);

        // Second consume of the same id fails (single-use).
        assertThat(store.consume("n1", "tok|tenant|dev")).isEmpty();
        assertThat(store.size()).isZero();
    }

    @Test
    void consume_failsWhenExpired() {
        MutableClock clock = new MutableClock(T0);
        InMemoryTpmNonceStore store = new InMemoryTpmNonceStore(clock);
        store.issue("n1", "scope", nonce, secret, T0.plusSeconds(300));

        clock.advance(Duration.ofSeconds(301));
        assertThat(store.consume("n1", "scope")).isEmpty();
        assertThat(store.size()).isZero(); // expired entry evicted on access
    }

    @Test
    void consume_scopeMismatch_doesNotBurnTheEntry() {
        MutableClock clock = new MutableClock(T0);
        InMemoryTpmNonceStore store = new InMemoryTpmNonceStore(clock);
        store.issue("n1", "correct-scope", nonce, secret, T0.plusSeconds(300));

        // A wrong-scope guess must NOT consume the legitimately-pending nonce.
        assertThat(store.consume("n1", "attacker-scope")).isEmpty();
        assertThat(store.size()).isEqualTo(1);

        // The correct scope still works afterwards.
        assertThat(store.consume("n1", "correct-scope")).isPresent();
    }

    @Test
    void consume_unknownId_isEmpty() {
        InMemoryTpmNonceStore store = new InMemoryTpmNonceStore(new MutableClock(T0));
        assertThat(store.consume("nope", "scope")).isEmpty();
    }

    @Test
    void evictExpired_removesOnlyExpired() {
        MutableClock clock = new MutableClock(T0);
        InMemoryTpmNonceStore store = new InMemoryTpmNonceStore(clock);
        store.issue("old", "s", nonce, secret, T0.plusSeconds(60));
        store.issue("new", "s", nonce, secret, T0.plusSeconds(600));

        clock.advance(Duration.ofSeconds(120));
        store.evictExpired();
        assertThat(store.size()).isEqualTo(1);
        assertThat(store.consume("new", "s")).isPresent();
    }
}
