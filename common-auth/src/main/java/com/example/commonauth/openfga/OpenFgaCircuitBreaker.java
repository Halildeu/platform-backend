package com.example.commonauth.openfga;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight in-memory circuit breaker for OpenFGA calls.
 * No external dependency required (pure Java).
 *
 * States:
 * - CLOSED: normal operation, count consecutive failures
 * - OPEN: short-circuit all calls, return fail-closed (false/empty)
 * - HALF_OPEN: allow one probe request; if success → CLOSED, if fail → OPEN
 *
 * Defaults: 5 consecutive failures → OPEN for 30s → HALF_OPEN probe.
 * Configure via constructor or OpenFGA properties.
 */
public class OpenFgaCircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaCircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final Duration openDuration;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile Instant openedAt = Instant.MIN;

    public OpenFgaCircuitBreaker(int failureThreshold, Duration openDuration) {
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
    }

    /** Default: 5 failures → open for 30s. */
    public OpenFgaCircuitBreaker() {
        this(5, Duration.ofSeconds(30));
    }

    /**
     * Check if a call should be allowed through.
     * @return true if call is allowed (CLOSED or HALF_OPEN probe)
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            if (Instant.now().isAfter(openedAt.plus(openDuration))) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("[circuit-breaker] OpenFGA circuit HALF_OPEN — allowing probe request");
                }
                return true; // Allow one probe
            }
            return false; // Still in cool-down
        }
        // HALF_OPEN — allow the probe
        return true;
    }

    /** Record a successful call. Resets circuit to CLOSED. */
    public void recordSuccess() {
        State previous = state.getAndSet(State.CLOSED);
        consecutiveFailures.set(0);
        if (previous != State.CLOSED) {
            log.info("[circuit-breaker] OpenFGA circuit CLOSED — recovered after successful probe");
        }
    }

    /** Record a failed call. May trip circuit to OPEN. */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            State previous = state.getAndSet(State.OPEN);
            openedAt = Instant.now();
            if (previous != State.OPEN) {
                log.error("[circuit-breaker] OpenFGA circuit OPEN — {} consecutive failures, "
                        + "short-circuiting for {}s", failures, openDuration.toSeconds());
            }
        }
    }

    /** Current state for monitoring/metrics. */
    public State getState() {
        // Refresh OPEN→HALF_OPEN on read if cool-down expired
        if (state.get() == State.OPEN && Instant.now().isAfter(openedAt.plus(openDuration))) {
            state.compareAndSet(State.OPEN, State.HALF_OPEN);
        }
        return state.get();
    }

    /** Consecutive failure count for monitoring. */
    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
