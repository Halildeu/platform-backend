package com.example.audiogateway.service;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Hands out monotonically increasing ids for transcript sequence spaces (Faz 24 —
 * transcript readability).
 *
 * <p><b>Why an epoch and not just the transport.</b> Window sequences restart at 0 every
 * time a new sequence space opens — a fresh REST session buffer, a fresh WebSocket leg.
 * Knowing only WHICH transport produced a result is not enough to tell those apart: a
 * socket that drops and reconnects starts at 0 again on the same transport, and a
 * downstream reorder buffer would reject the reconnected leg's speech as replayed. Worse,
 * a completion straggling in from the old leg after the new one started would look like a
 * switch back and forth, thrashing the buffer.
 *
 * <p>An epoch fixes both: a consumer advances only to a HIGHER epoch, and anything
 * carrying a lower one is recognised as a straggler from a space that is already closed
 * rather than being allowed to reopen it.
 *
 * <p>Ids are process-wide and start above zero so an unset field ({@code 0}) is never
 * mistaken for a real epoch. They need only outlive a session, and a session cannot
 * outlive the process that holds its buffers.
 */
final class SequenceEpochs {

    private static final AtomicLong COUNTER = new AtomicLong();

    private SequenceEpochs() {}

    /** A fresh epoch for a newly opened sequence space. Always {@code >= 1}. */
    static long next() {
        return COUNTER.incrementAndGet();
    }
}
