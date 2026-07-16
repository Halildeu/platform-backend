package com.example.common.meeting.events.conformance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The kits actually REJECT a broken implementation — the guard on #802 acceptance 6.
 *
 * <h2>Why this test exists</h2>
 * A conformance kit that only ever runs against a correct reference proves that the rules
 * are satisfiable, not that they are enforced. The first version of
 * {@link OutboxConformanceKit} claimed to pin "exactly-once EFFECT" while a consumer that
 * applied two effects would have passed every test in it, and its duplicate-key assertion
 * accepted ANY exception — both would have gone on being believed indefinitely, because
 * the only implementation exercising them was correct by construction.
 *
 * <p>So each test below feeds a deliberately non-conformant harness to a kit and asserts
 * the kit FAILS. If someone later weakens a rule, the corresponding test here goes green
 * where it should be red, and the weakening surfaces immediately.
 *
 * <p>The kits' {@code @BeforeEach} and {@code @Test} methods are package-private, so this
 * class drives them directly rather than through the JUnit engine — the assertions are the
 * same code the engine would run.
 */
class ConformanceKitTeethTest {

    // ────────────────────────── consumer inbox kit ──────────────────────────

    private static ConsumerInboxConformanceKit inboxKitOver(final InboxTestHarness harness) {
        final ConsumerInboxConformanceKit kit = new ConsumerInboxConformanceKit() {
            @Override
            protected InboxTestHarness harness() {
                return harness;
            }
        };
        kit.resetConsumer();
        return kit;
    }

    @Test
    void kitRejectsAConsumerThatAppliesTheEffectTwiceOnRedelivery() {
        // The exact false positive the review caught: identical bytes, two side effects.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.NoDedupInbox());

        assertThatThrownBy(kit::redeliveryOfTheSameEventDoesNotApplyASecondEffect)
                .as("a consumer with no inbox must fail the exactly-once-effect rule")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void kitRejectsAConsumerThatAppliesTheEffectOutsideTheInboxTransaction() {
        // Looks correct on the happy path; leaves an orphan effect when its transaction dies.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.EffectOutsideTransactionInbox());

        assertThatThrownBy(kit::aFailedConsumerTransactionLeavesNoEffect)
                .as("an effect applied outside the inbox transaction must fail the atomicity rule")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void kitRejectsAConsumerWhoseDedupIsAOneSlotCacheRatherThanADurableInbox() {
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.LastSeenOnlyInbox());

        assertThatThrownBy(kit::deliveryAfterAnAppliedEffectIsSuppressedEvenAcrossOtherTraffic)
                .as("a last-seen-only cache must fail once other traffic is interleaved")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aOneSlotCachePassesTheNaiveRedeliveryTest_whichIsWhyTheInterleavedRuleExists() {
        // Documents WHY the interleaved rule is not redundant: the obvious back-to-back
        // redelivery test alone would bless this broken consumer.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.LastSeenOnlyInbox());

        assertThatCode(kit::redeliveryOfTheSameEventDoesNotApplyASecondEffect)
                .doesNotThrowAnyException();
    }

    @Test
    void kitRejectsAConsumerWhoseFailureInjectionQuietlyDoesNothing() {
        // The consumer-side twin of the duplicate-exception defect: an atomicity rule that
        // passes VACUOUSLY. This harness de-duplicates perfectly and claims its injection
        // ran, so "no effect committed" is true — but only because it never attempted one.
        // The outcome alone cannot catch it; the independent attempted-effect observation can.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.NoOpFailureInjectionInbox());

        assertThatThrownBy(kit::theFailureInjectionActuallyRuns_orTheAtomicityRulesProveNothing)
                .as("a no-op fault injection must not be able to satisfy the atomicity rules")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aNoOpFailureInjectionPassesTheNaiveZeroEffectCheck_whichIsWhyTheEvidenceRuleExists() {
        // Documents WHY the evidence rule is not redundant: the obvious "did it leave an
        // effect behind?" assertion alone blesses a harness that did nothing at all.
        BrokenHarnesses.NoOpFailureInjectionInbox noop = new BrokenHarnesses.NoOpFailureInjectionInbox();
        noop.reset();

        assertThat(noop.deliverWithFailureAfterEffect(
                inboxKitOver(noop).summaryReadyDelivery(UUID.randomUUID())).transactionFailed())
                .as("it cheerfully claims the transaction failed")
                .isTrue();
        assertThat(noop.totalEffectCount())
                .as("and committed nothing — the naive check is satisfied")
                .isZero();
    }

    @Test
    void kitRejectsACheckThenActConsumerThatLosesTheConcurrentCollision() throws Exception {
        // The rule the review asked for. This consumer reads "have I seen this key?" then
        // writes, with no atomic claim — a plain SELECT then INSERT, or an inbox table with
        // no UNIQUE index on event_key. It passes every sequential test; under a real
        // collision two workers both pass the read and the notification goes out twice.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.CheckThenActInbox(8));

        assertThatThrownBy(kit::concurrentDeliveriesOfTheSameEventApplyExactlyOneEffect)
                .as("a check-then-act inbox must fail the concurrent collision rule")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void aCheckThenActConsumerPassesEverySequentialRule_whichIsWhyTheCollisionRuleExists() {
        // Documents WHY the concurrent rule is not redundant: single-thread redelivery
        // tests bless this consumer completely.
        ConsumerInboxConformanceKit kit = inboxKitOver(new BrokenHarnesses.CheckThenActInbox(1));

        assertThatCode(() -> {
            kit.redeliveryOfTheSameEventDoesNotApplyASecondEffect();
            kit.resetConsumer();
            kit.repeatedRedeliveryStillAppliesExactlyOneEffect();
        }).doesNotThrowAnyException();
    }

    @Test
    void theReferenceInboxPassesTheRulesTheBrokenOnesFail() throws Exception {
        // Guards against the opposite failure: rules so strict nothing can satisfy them.
        ConsumerInboxConformanceKit kit = inboxKitOver(new InMemoryInboxHarness());

        assertThatCode(() -> {
            kit.redeliveryOfTheSameEventDoesNotApplyASecondEffect();
            kit.resetConsumer();
            kit.theFailureInjectionActuallyRuns_orTheAtomicityRulesProveNothing();
            kit.resetConsumer();
            kit.aFailedConsumerTransactionLeavesNoEffect();
            kit.resetConsumer();
            kit.deliveryAfterAnAppliedEffectIsSuppressedEvenAcrossOtherTraffic();
            kit.resetConsumer();
            kit.concurrentDeliveriesOfTheSameEventApplyExactlyOneEffect();
            kit.resetConsumer();
            kit.concurrentDeliveriesOfDistinctEventsEachApplyTheirOwnEffect();
        }).doesNotThrowAnyException();
    }

    // ────────────────────────── producer outbox kit ──────────────────────────

    private static OutboxConformanceKit outboxKitOver(final OutboxTestHarness harness) {
        final OutboxConformanceKit kit = new OutboxConformanceKit() {
            @Override
            protected OutboxTestHarness harness() {
                return harness;
            }
        };
        kit.resetStore();
        return kit;
    }

    @Test
    void kitRejectsAnOutboxWithNoUniqueIndex() {
        OutboxConformanceKit kit = outboxKitOver(new BrokenHarnesses.NoUniqueIndexOutbox());

        assertThatThrownBy(kit::duplicateAppendOfTheSameKeyIsRejected_theUniqueIndexIsTheIdempotencyGuarantee)
                .as("an outbox that silently accepts a duplicate key must fail")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void kitRejectsAnOutboxWhoseDuplicateFailsForAnUnrelatedReason() {
        // The review's second point: before the fix, ANY exception kept this green.
        OutboxConformanceKit kit = outboxKitOver(new BrokenHarnesses.WrongExceptionOutbox());

        assertThatThrownBy(kit::duplicateAppendOfTheSameKeyIsRejected_theUniqueIndexIsTheIdempotencyGuarantee)
                .as("a non-duplicate-key failure must not satisfy the idempotency rule")
                .isInstanceOf(AssertionError.class);
    }

    @Test
    void theReferenceOutboxPassesTheDuplicateRuleTheBrokenOnesFail() {
        OutboxConformanceKit kit = outboxKitOver(new InMemoryOutboxHarness());

        assertThatCode(kit::duplicateAppendOfTheSameKeyIsRejected_theUniqueIndexIsTheIdempotencyGuarantee)
                .doesNotThrowAnyException();
    }

    @Test
    void theReferenceOutboxReportsDuplicatesAsTheContractRequires() {
        // The SPI contract itself, stated once as an executable expectation.
        OutboxTestHarness store = new InMemoryOutboxHarness();
        store.reset();
        UUID runId = UUID.randomUUID();
        var event = outboxKitOver(store).summaryReady(runId);
        store.reset();
        store.appendInTransaction(List.of(event), true);

        assertThatThrownBy(() -> store.appendInTransaction(List.of(event), true))
                .isInstanceOf(DuplicateEventKeyException.class)
                .hasMessageContaining(event.eventKey());
    }
}
