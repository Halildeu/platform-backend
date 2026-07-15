package com.example.common.meeting.events.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;
import com.example.common.meeting.events.MeetingEventV1Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The outbox/poller behaviour every meeting-event producer must satisfy — #802 slice 1,
 * first-PR acceptance 6.
 *
 * <p>Extend it, return your store from {@link #harness()}, and these rules run against
 * YOUR outbox:
 *
 * <pre>{@code
 * class MyOutboxConformanceTest extends OutboxConformanceKit {
 *     private final OutboxTestHarness harness = new MyPostgresHarness(...);
 *     @Override protected OutboxTestHarness harness() { return harness; }
 * }
 * }</pre>
 *
 * <p>The rules are a kit rather than a checklist in a doc because "we handle retries"
 * is the kind of claim that is true until the second producer implements it slightly
 * differently. Each new producer (audio-gateway consent, transcript-service
 * finalization) inherits the same executable definition of correct.
 *
 * <h2>The delivery guarantee being pinned</h2>
 * The transport gives at-least-once: the crash window between "delivery succeeded" and
 * "marked published" is real and cannot be closed by ordering alone. So the contract is
 * at-least-once DELIVERY plus exactly-once EFFECT, and the effect half rests on the
 * deterministic event key — which is why redelivery must reproduce the key and the bytes
 * exactly, and why {@link #redeliveryAfterCrash_reproducesTheSameKeyAndBytes} is the
 * load-bearing test here rather than a nicety.
 */
public abstract class OutboxConformanceKit {

    /** The store under test. Called once per test; may return the same instance. */
    protected abstract OutboxTestHarness harness();

    private OutboxTestHarness store;

    @BeforeEach
    void resetStore() {
        store = harness();
        store.reset();
        assertThat(store.maxDeliveryAttempts())
                .as("maxDeliveryAttempts must be >= 1 or nothing can ever be delivered")
                .isGreaterThanOrEqualTo(1);
    }

    // ────────────────────────── commit-before/after publish crash window ──────────────────────────

    @Test
    void committedEventsBecomePublishable() {
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());

        store.appendInTransaction(List.of(event), true);

        assertThat(store.pollPublishable(10))
                .extracting(StoredOutboxEvent::eventKey)
                .containsExactly(event.eventKey());
    }

    @Test
    void rolledBackEventsNeverExist_noEventForAWriteThatDidNotHappen() {
        // The crash window BEFORE commit. If a rolled-back run could still emit, a
        // consumer would act on an analysis that does not exist in the database.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());

        store.appendInTransaction(List.of(event), false);

        assertThat(store.findByEventKey(event.eventKey())).isEmpty();
        assertThat(store.pollPublishable(10)).isEmpty();
    }

    @Test
    void crashAfterCommitBeforePublish_leavesTheEventPublishable_notLost() {
        // The crash window AFTER commit: the process dies before the poller delivers.
        // The row must survive and still be claimable — this is why the event is written
        // in the transaction rather than published from it.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        // ... poller claims it, then the process dies before markPublished ...

        assertThat(store.pollPublishable(10))
                .as("an unacknowledged event must remain publishable after a crash")
                .extracting(StoredOutboxEvent::eventKey)
                .containsExactly(event.eventKey());
    }

    @Test
    void publishedEventsAreNotPolledAgain() {
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        store.markPublished(event.eventKey());

        assertThat(store.pollPublishable(10)).isEmpty();
        assertThat(store.findByEventKey(event.eventKey()))
                .get().extracting(StoredOutboxEvent::published).isEqualTo(true);
    }

    @Test
    void markPublishedIsIdempotent_aRetriedAckIsNotAnError() {
        // The same crash window, from the other side: a poller that dies after
        // markPublished and retries on restart must not blow up.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        store.markPublished(event.eventKey());
        store.markPublished(event.eventKey());

        assertThat(store.pollPublishable(10)).isEmpty();
    }

    // ────────────────────────── duplicate / exactly-once effect ──────────────────────────

    @Test
    void duplicateAppendOfTheSameKeyIsRejected_theUniqueIndexIsTheIdempotencyGuarantee() {
        // A retried ingestion of the same run must not double-emit. The store — not the
        // application — must be the thing that says no, because two racing writers both
        // see "no existing row" before either commits.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        assertThatThrownBy(() -> store.appendInTransaction(List.of(event), true))
                .as("a second append of the same event key must be rejected by the store");

        assertThat(store.pollPublishable(10)).hasSize(1);
    }

    @Test
    void redeliveryAfterCrash_reproducesTheSameKeyAndBytes() {
        // At-least-once delivery is only survivable because the redelivered copy is
        // IDENTICAL: same key for the consumer to de-duplicate on, same bytes so the two
        // copies cannot be interpreted differently. If either drifted, a duplicate
        // delivery would become a second, distinct effect.
        UUID runId = UUID.randomUUID();
        MeetingEventEnvelope first = summaryReady(runId);
        store.appendInTransaction(List.of(first), true);

        StoredOutboxEvent delivered = store.pollPublishable(10).get(0);
        StoredOutboxEvent redelivered = store.pollPublishable(10).get(0);

        assertThat(redelivered.eventKey()).isEqualTo(delivered.eventKey());
        assertThat(redelivered.payloadJson()).isEqualTo(delivered.payloadJson());
        // And the key a fresh rebuild of the same fact derives is still the same one.
        assertThat(summaryReady(runId).eventKey()).isEqualTo(delivered.eventKey());
    }

    @Test
    void distinctFactsGetDistinctKeys_noAccidentalCollapse() {
        // The mirror of de-duplication: two real actions of one run must not collapse
        // onto a single key, or only the first would ever be delivered.
        UUID runId = UUID.randomUUID();
        MeetingEventEnvelope action0 = actionAssigned(runId, 0);
        MeetingEventEnvelope action1 = actionAssigned(runId, 1);

        store.appendInTransaction(List.of(action0, action1), true);

        assertThat(store.pollPublishable(10))
                .extracting(StoredOutboxEvent::eventKey)
                .containsExactlyInAnyOrder(action0.eventKey(), action1.eventKey())
                .doesNotHaveDuplicates();
    }

    @Test
    void allEventsOfOneTransactionCommitTogether() {
        UUID runId = UUID.randomUUID();
        List<MeetingEventEnvelope> batch = List.of(
                summaryReady(runId), actionAssigned(runId, 0), actionAssigned(runId, 1));

        store.appendInTransaction(batch, false);
        assertThat(store.pollPublishable(10)).as("a rolled-back batch emits nothing").isEmpty();

        store.appendInTransaction(batch, true);
        assertThat(store.pollPublishable(10)).as("a committed batch emits all of it").hasSize(3);
    }

    // ────────────────────────── retry / dead-letter ──────────────────────────

    @Test
    void aFailedAttemptLeavesTheEventPublishable_soItIsRetried() {
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        store.markAttemptFailed(event.eventKey(), "broker unavailable");

        assertThat(store.pollPublishable(10))
                .as("a transient failure must not drop the event")
                .extracting(StoredOutboxEvent::eventKey)
                .containsExactly(event.eventKey());
        assertThat(store.findByEventKey(event.eventKey()))
                .get().extracting(StoredOutboxEvent::attempts).isEqualTo(1);
    }

    @Test
    void attemptsAreCounted_soRetryIsBoundedRatherThanInfinite() {
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        for (int i = 1; i <= store.maxDeliveryAttempts(); i++) {
            store.markAttemptFailed(event.eventKey(), "attempt " + i);
            assertThat(store.findByEventKey(event.eventKey()))
                    .get().extracting(StoredOutboxEvent::attempts).isEqualTo(i);
        }
    }

    @Test
    void exhaustedAttempts_deadLetterTheEventInsteadOfBlockingTheQueueForever() {
        // A permanently undeliverable event must stop being retried. Otherwise one poison
        // row consumes the poller's budget forever and starves every healthy event —
        // an availability failure caused by a single bad message.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        for (int i = 0; i < store.maxDeliveryAttempts(); i++) {
            store.markAttemptFailed(event.eventKey(), "permanently broken");
        }

        assertThat(store.findByEventKey(event.eventKey()))
                .get().extracting(StoredOutboxEvent::deadLettered).isEqualTo(true);
        assertThat(store.pollPublishable(10))
                .as("a dead-lettered event must not be polled again")
                .isEmpty();
    }

    @Test
    void deadLetteringIsNotDeletion_theEvidenceSurvivesForRepair() {
        // The row must remain inspectable and replayable: dead-lettering is a decision to
        // stop auto-retrying, not a decision to forget the fact happened.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        for (int i = 0; i < store.maxDeliveryAttempts(); i++) {
            store.markAttemptFailed(event.eventKey(), "permanently broken");
        }

        assertThat(store.findByEventKey(event.eventKey()))
                .as("a dead-lettered event is parked, not deleted")
                .isPresent()
                .get()
                .satisfies(row -> {
                    assertThat(row.published()).isFalse();
                    assertThat(row.payloadJson()).isNotBlank();
                });
    }

    @Test
    void oneDeadLetteredEventDoesNotBlockHealthyOnes() {
        UUID poisonRun = UUID.randomUUID();
        MeetingEventEnvelope poison = summaryReady(poisonRun);
        MeetingEventEnvelope healthy = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(poison), true);
        store.appendInTransaction(List.of(healthy), true);

        for (int i = 0; i < store.maxDeliveryAttempts(); i++) {
            store.markAttemptFailed(poison.eventKey(), "poison");
        }

        assertThat(store.pollPublishable(10))
                .extracting(StoredOutboxEvent::eventKey)
                .containsExactly(healthy.eventKey());
    }

    // ────────────────────────── stored bytes ──────────────────────────

    @Test
    void theStoredPayloadIsTheV1Wire_notSomeStoreSpecificRendering() {
        // What the poller ships must be what the serializer produced; a store that
        // re-renders (a JSONB round-trip that reorders keys, say) would silently change
        // the bytes every pinned consumer parses.
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        assertThat(store.pollPublishable(10).get(0).payloadJson())
                .isEqualTo(MeetingEventV1Serializer.toJson(event));
    }

    @Test
    void theStoredEventTypeIsTheCanonicalWireValue() {
        MeetingEventEnvelope event = summaryReady(UUID.randomUUID());
        store.appendInTransaction(List.of(event), true);

        assertThat(store.pollPublishable(10).get(0).eventType())
                .isEqualTo(MeetingEventType.SUMMARY_READY.wireValue());
    }

    @Test
    void pollRespectsItsLimit_soAPollerCanBoundItsBatch() {
        List<MeetingEventEnvelope> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            many.add(summaryReady(UUID.randomUUID()));
        }
        for (MeetingEventEnvelope event : many) {
            store.appendInTransaction(List.of(event), true);
        }

        assertThat(store.pollPublishable(2)).hasSize(2);
    }

    // ────────────────────────── fixtures ──────────────────────────

    /** A valid {@code summary.ready} for an arbitrary run — the kit's generic fact. */
    protected MeetingEventEnvelope summaryReady(final UUID runId) {
        return base(MeetingEventType.SUMMARY_READY, runId)
                .payload(new MeetingEventPayload.SummaryReady(runId, "verified", 1, 1))
                .build();
    }

    /** A valid {@code action.assigned} for an arbitrary run and ordinal. */
    protected MeetingEventEnvelope actionAssigned(final UUID runId, final int ordinal) {
        return base(MeetingEventType.ACTION_ASSIGNED, runId)
                .payload(new MeetingEventPayload.ActionAssigned(
                        runId, ordinal, "ali@example.com", MeetingEventGoldens.DUE_AT))
                .build();
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
