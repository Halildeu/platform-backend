package com.example.common.meeting.events.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The de-duplication behaviour every meeting-event CONSUMER must satisfy — #802 slice 1,
 * first-PR acceptance 6 (duplicate exactly-once-effect).
 *
 * <p>Extend it, return your consumer from {@link #harness()}, and these rules run against
 * YOUR inbox:
 *
 * <pre>{@code
 * class MyConsumerInboxConformanceTest extends ConsumerInboxConformanceKit {
 *     private final InboxTestHarness harness = new MyNotificationInboxHarness(...);
 *     @Override protected InboxTestHarness harness() { return harness; }
 * }
 * }</pre>
 *
 * <h2>Why this is a separate kit from {@link OutboxConformanceKit}</h2>
 * Exactly-once effect has two halves on opposite sides of the wire, and neither side can
 * prove it alone. The producer's half is that a redelivered event is byte-identical, so a
 * consumer CAN recognise it — that is all the outbox kit proves. The consumer's half is
 * that it actually applies one effect, which can only be shown by counting effects while
 * driving a real consumer. Folding both into one kit would let a producer's green run
 * imply a guarantee no consumer had demonstrated.
 *
 * <p>This kit counts REAL side effects, not inbox rows. An inbox row is the consumer's own
 * bookkeeping: asking it whether it de-duplicated is asking it to grade its own work. The
 * question that matters is whether the notification was sent twice.
 *
 * <h2>Scope — what a green run here does and does not license</h2>
 * The kit pins the CONTRACT: an atomic claim per {@code eventKey}, effect and inbox entry
 * committing together, and one effect under a real in-process collision. It is written
 * against whatever store the harness wraps, so a green run says the consumer's logic
 * honours the contract on that store.
 *
 * <p>It is NOT a substitute for exercising the real store under real concurrency. The
 * failures that matter most — a missing UNIQUE index on {@code event_key}, an isolation
 * level that lets two transactions both read "absent", a claim that races across
 * processes rather than threads — are properties of the deployed database, not of the
 * logic above it. Binding a producer's real Postgres inbox to this kit is a later slice
 * (#802 acceptance 6 asks for the contract to be DEFINED here). What this kit does
 * guarantee is that it will not leave the collision path falsely green in the meantime:
 * {@link #concurrentDeliveriesOfTheSameEventApplyExactlyOneEffect} fails a check-then-act
 * consumer, and {@code ConformanceKitTeethTest} proves it.
 */
public abstract class ConsumerInboxConformanceKit {

    /** The consumer under test. Called once per test; may return the same instance. */
    protected abstract InboxTestHarness harness();

    private InboxTestHarness consumer;

    @BeforeEach
    void resetConsumer() {
        consumer = harness();
        consumer.reset();
        assertThat(consumer.totalEffectCount())
                .as("reset() must leave the consumer with no applied effects")
                .isZero();
    }

    // ────────────────────────── the headline: one effect per fact ──────────────────────────

    @Test
    void firstDeliveryAppliesTheEffect() {
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());

        assertThat(consumer.deliver(delivery))
                .as("a brand-new event must apply its effect")
                .isTrue();
        assertThat(consumer.effectCount(delivery.eventKey())).isEqualTo(1);
    }

    @Test
    void redeliveryOfTheSameEventDoesNotApplyASecondEffect() {
        // THE rule. The transport is at-least-once, so this delivery WILL happen in
        // production: the consumer acted, then died before acknowledging, and the broker
        // handed the same event back. If a second notification goes out here, a user is
        // told twice that their summary is ready.
        InboxDelivery first = summaryReadyDelivery(UUID.randomUUID());
        consumer.deliver(first);

        boolean appliedAgain = consumer.deliver(first.redelivery());

        assertThat(appliedAgain)
                .as("a redelivered event must be recognised as a duplicate")
                .isFalse();
        assertThat(consumer.effectCount(first.eventKey()))
                .as("exactly-once EFFECT: the side effect happened once, not twice")
                .isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
    }

    @Test
    void repeatedRedeliveryStillAppliesExactlyOneEffect() {
        // Redelivery is not limited to one retry: a consumer stuck in a crash loop, or a
        // broker replaying a backlog, can hand the same event back many times.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());

        for (int i = 0; i < 10; i++) {
            consumer.deliver(delivery.redelivery());
        }

        assertThat(consumer.effectCount(delivery.eventKey())).isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
    }

    @Test
    void distinctEventsEachApplyTheirOwnEffect() {
        // The mirror of de-duplication, and the reason it must key on eventKey rather than
        // on something coarser: an over-eager inbox that swallowed real events would pass
        // every test above while silently dropping user-visible work.
        UUID runId = UUID.randomUUID();
        InboxDelivery summary = summaryReadyDelivery(runId);
        InboxDelivery action0 = actionAssignedDelivery(runId, 0);
        InboxDelivery action1 = actionAssignedDelivery(runId, 1);

        assertThat(consumer.deliver(summary)).isTrue();
        assertThat(consumer.deliver(action0)).isTrue();
        assertThat(consumer.deliver(action1)).isTrue();

        assertThat(consumer.effectCount(summary.eventKey())).isEqualTo(1);
        assertThat(consumer.effectCount(action0.eventKey())).isEqualTo(1);
        assertThat(consumer.effectCount(action1.eventKey())).isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(3);
    }

    @Test
    void deDuplicationIsPerEventKey_notPerEventType() {
        // Two summary.ready events for different runs share a type but are different facts.
        // An inbox keyed on type (or on aggregate alone) would deliver only the first.
        InboxDelivery runA = summaryReadyDelivery(UUID.randomUUID());
        InboxDelivery runB = summaryReadyDelivery(UUID.randomUUID());

        consumer.deliver(runA);
        assertThat(consumer.deliver(runB))
                .as("a different run's summary is a different fact, not a duplicate")
                .isTrue();

        assertThat(consumer.totalEffectCount()).isEqualTo(2);
    }

    // ────────────────────────── the effect/inbox atomicity window ──────────────────────────

    @Test
    void theFailureInjectionActuallyRuns_orTheAtomicityRulesProveNothing() {
        // Checked FIRST and on its own, because every rule below is vacuous without it: a
        // deliverWithFailureAfterEffect that quietly does nothing would satisfy "no effect
        // committed" by never attempting one. That is the consumer-side twin of the defect
        // DuplicateEventKeyException closed on the producer side — an empty result keeping
        // a test green.
        //
        // The harness's own outcome is not taken on trust: attemptedEffectCount is an
        // independent observation it cannot satisfy without genuinely running the path.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());

        InboxTestHarness.FailureInjection injection = consumer.deliverWithFailureAfterEffect(delivery);

        assertThat(injection)
                .as("the harness must report what its injection did")
                .isNotNull();
        assertThat(injection.effectPointReached())
                .as("the injection must reach the effect point, not skip it")
                .isTrue();
        assertThat(injection.transactionFailed())
                .as("the injection must fail the transaction, not commit it")
                .isTrue();
        assertThat(consumer.attemptedEffectCount(delivery.eventKey()))
                .as("and the attempt must be independently observable, corroborating the outcome")
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void aFailedConsumerTransactionLeavesNoEffect() {
        // If the effect is applied outside the transaction that records the inbox entry,
        // it survives the rollback — and the redelivery that follows adds a second one.
        // Zero COMMITTED effects next to a non-zero ATTEMPT is what proves the two commit
        // together: it says the consumer tried and unwound, not that it never tried.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());

        InboxTestHarness.FailureInjection injection = consumer.deliverWithFailureAfterEffect(delivery);

        assertThat(injection.effectPointReached()).isTrue();
        assertThat(consumer.attemptedEffectCount(delivery.eventKey())).isGreaterThanOrEqualTo(1);
        assertThat(consumer.effectCount(delivery.eventKey()))
                .as("effect and inbox entry commit together, so a failed transaction leaves neither")
                .isZero();
        assertThat(consumer.totalEffectCount()).isZero();
    }

    @Test
    void afterAFailedTransactionTheRedeliveryAppliesExactlyOneEffect() {
        // The whole point of leaving nothing behind: the event is not lost. The broker
        // redelivers it and the effect happens — once, not zero times and not twice.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());
        InboxTestHarness.FailureInjection injection = consumer.deliverWithFailureAfterEffect(delivery);
        assertThat(injection.effectPointReached()).isTrue();

        assertThat(consumer.deliver(delivery.redelivery()))
                .as("a retry after a failed transaction must still apply the effect")
                .isTrue();

        assertThat(consumer.effectCount(delivery.eventKey())).isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
    }

    @Test
    void aFailedTransactionDoesNotDiscardAlreadyAppliedEffects() {
        // One poison delivery must not roll back a neighbour's committed effect.
        InboxDelivery healthy = summaryReadyDelivery(UUID.randomUUID());
        InboxDelivery poison = summaryReadyDelivery(UUID.randomUUID());
        consumer.deliver(healthy);

        InboxTestHarness.FailureInjection injection = consumer.deliverWithFailureAfterEffect(poison);
        assertThat(injection.effectPointReached()).isTrue();

        assertThat(consumer.effectCount(healthy.eventKey())).isEqualTo(1);
        assertThat(consumer.effectCount(poison.eventKey())).isZero();
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
    }

    // ────────────────────────── concurrent duplicate collision ──────────────────────────

    @Test
    void concurrentDeliveriesOfTheSameEventApplyExactlyOneEffect() throws Exception {
        // Sequential redelivery is the easy half. The half that actually breaks consumers
        // is this one: two workers claim the same event at once. A consumer that reads
        // "have I seen this key?" and then writes passes every sequential test here while
        // both workers sail past the read and both send the notification. Only the store's
        // own atomicity — INSERT … ON CONFLICT DO NOTHING on a UNIQUE event_key, or an
        // equivalent atomic claim — resolves the race to one winner.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());
        int workers = 8;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CyclicBarrier allAtOnce = new CyclicBarrier(workers);
        AtomicInteger claimedIt = new AtomicInteger();
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    // The barrier makes the collision real rather than hoped for: every
                    // worker is inside deliver() at the same moment.
                    allAtOnce.await(10, TimeUnit.SECONDS);
                    if (consumer.deliver(delivery.redelivery())) {
                        claimedIt.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(consumer.effectCount(delivery.eventKey()))
                .as("%d workers raced for one event: the notification goes out once", workers)
                .isEqualTo(1);
        assertThat(claimedIt.get())
                .as("and exactly one worker may believe it was the one that applied it")
                .isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
    }

    @Test
    void concurrentDeliveriesOfDistinctEventsEachApplyTheirOwnEffect() throws Exception {
        // The mirror: an inbox whose claim is too coarse (a global lock keyed on nothing,
        // a table-level claim) would serialise correctly AND swallow real work.
        int events = 16;
        List<InboxDelivery> deliveries = new ArrayList<>();
        for (int i = 0; i < events; i++) {
            deliveries.add(summaryReadyDelivery(UUID.randomUUID()));
        }
        // One thread per party: a barrier with more parties than the pool can run at once
        // never trips, and the test would hang rather than measure anything.
        ExecutorService pool = Executors.newFixedThreadPool(events);
        CyclicBarrier allAtOnce = new CyclicBarrier(events);
        List<Future<?>> futures = new ArrayList<>();

        try {
            for (InboxDelivery d : deliveries) {
                futures.add(pool.submit(() -> {
                    allAtOnce.await(10, TimeUnit.SECONDS);
                    return consumer.deliver(d);
                }));
            }
            for (Future<?> f : futures) {
                f.get(20, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(consumer.totalEffectCount()).isEqualTo(events);
        for (InboxDelivery d : deliveries) {
            assertThat(consumer.effectCount(d.eventKey())).isEqualTo(1);
        }
    }

    @Test
    void deliveryAfterAnAppliedEffectIsSuppressedEvenAcrossOtherTraffic() {
        // De-duplication must be durable state, not a "last event seen" memory: real
        // consumers interleave sessions, and a one-slot cache would let the redelivery of
        // an older event through.
        InboxDelivery first = summaryReadyDelivery(UUID.randomUUID());
        consumer.deliver(first);
        for (int i = 0; i < 5; i++) {
            consumer.deliver(summaryReadyDelivery(UUID.randomUUID()));
        }

        assertThat(consumer.deliver(first.redelivery()))
                .as("an older event's redelivery must still be recognised")
                .isFalse();
        assertThat(consumer.effectCount(first.eventKey())).isEqualTo(1);
        assertThat(consumer.totalEffectCount()).isEqualTo(6);
    }

    // ────────────────────────── fixtures ──────────────────────────

    /** A valid {@code summary.ready} delivery for an arbitrary run. */
    protected InboxDelivery summaryReadyDelivery(final UUID runId) {
        return InboxDelivery.of(base(MeetingEventType.SUMMARY_READY, runId)
                .payload(new MeetingEventPayload.SummaryReady(runId, "verified", 1, 1))
                .build());
    }

    /** A valid {@code action.assigned} delivery for an arbitrary run and ordinal. */
    protected InboxDelivery actionAssignedDelivery(final UUID runId, final int ordinal) {
        return InboxDelivery.of(base(MeetingEventType.ACTION_ASSIGNED, runId)
                .payload(new MeetingEventPayload.ActionAssigned(
                        runId, ordinal, "ali@example.com", MeetingEventGoldens.DUE_AT))
                .build());
    }

    private MeetingEventEnvelope.Builder base(final MeetingEventType type, final UUID runId) {
        return MeetingEventEnvelope.builder()
                .eventType(type)
                .producer("conformance-kit")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .orgId(MeetingEventGoldens.ORG_ID)
                .occurredAt(MeetingEventGoldens.GENERATED_AT)
                .aggregateType("meeting.analysis.run")
                .aggregateId(runId)
                .aggregateRevision(0);
    }
}
