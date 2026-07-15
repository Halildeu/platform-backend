package com.example.common.meeting.events.conformance;

/**
 * Runs {@link OutboxConformanceKit} against the reference in-memory outbox.
 *
 * <p>This is the kit testing itself: it proves the rules are executable and mutually
 * satisfiable, and it is the worked example a producer copies when binding the kit to
 * its own store.
 */
class InMemoryOutboxConformanceTest extends OutboxConformanceKit {

    private final OutboxTestHarness harness = new InMemoryOutboxHarness();

    @Override
    protected OutboxTestHarness harness() {
        return harness;
    }
}
