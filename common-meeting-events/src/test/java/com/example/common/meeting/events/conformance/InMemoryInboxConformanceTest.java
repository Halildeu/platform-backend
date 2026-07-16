package com.example.common.meeting.events.conformance;

/**
 * Runs {@link ConsumerInboxConformanceKit} against the reference in-memory inbox.
 *
 * <p>Proves the rules are executable and mutually satisfiable, and is the worked example a
 * consumer copies when binding the kit to its own store. That the reference passes is
 * necessary but not sufficient — {@link ConformanceKitTeethTest} is what shows the rules
 * actually reject a broken consumer.
 */
class InMemoryInboxConformanceTest extends ConsumerInboxConformanceKit {

    private final InboxTestHarness harness = new InMemoryInboxHarness();

    @Override
    protected InboxTestHarness harness() {
        return harness;
    }
}
