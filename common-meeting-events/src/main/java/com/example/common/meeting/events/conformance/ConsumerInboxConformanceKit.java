package com.example.common.meeting.events.conformance;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;

import java.util.UUID;
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
    void aFailedConsumerTransactionLeavesNoEffect() {
        // If the effect is applied outside the transaction that records the inbox entry,
        // it survives the rollback — and the redelivery that follows adds a second one.
        // Zero effects here is what proves the two commit together.
        InboxDelivery delivery = summaryReadyDelivery(UUID.randomUUID());

        consumer.deliverWithFailureAfterEffect(delivery);

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
        consumer.deliverWithFailureAfterEffect(delivery);

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

        consumer.deliverWithFailureAfterEffect(poison);

        assertThat(consumer.effectCount(healthy.eventKey())).isEqualTo(1);
        assertThat(consumer.effectCount(poison.eventKey())).isZero();
        assertThat(consumer.totalEffectCount()).isEqualTo(1);
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
