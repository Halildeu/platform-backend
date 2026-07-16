package com.example.common.meeting.events.conformance;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * A minimal correct consumer inbox, used to prove {@link ConsumerInboxConformanceKit}
 * itself runs and that its rules are mutually satisfiable.
 *
 * <p>It models the one structural property the kit is really testing: the side effect and
 * the inbox entry commit TOGETHER. Both are staged and applied in a single step, so a
 * failure part-way leaves neither — exactly what a real consumer gets from wrapping the
 * effect and the inbox insert in one database transaction.
 *
 * <p>It is NOT a substitute for a real consumer running the kit against its real store:
 * the interesting failures (an inbox insert outside the effect's transaction, a missing
 * UNIQUE index on event_key, an in-memory "seen" set that empties on restart) are exactly
 * the ones a correct-by-construction fake cannot have. Its job is to keep the kit honest —
 * a rule that cannot be satisfied here is a rule with a bug in it.
 */
final class InMemoryInboxHarness implements InboxTestHarness {

    /** The durable inbox: event keys whose effect has committed. */
    private final Set<String> inbox = new HashSet<>();

    /** The real side effects, counted per key — what the kit actually asserts on. */
    private final Map<String, Integer> effects = new HashMap<>();

    @Override
    public boolean deliver(final InboxDelivery delivery) {
        if (inbox.contains(delivery.eventKey())) {
            return false; // already applied; suppress
        }
        commit(delivery.eventKey());
        return true;
    }

    @Override
    public void deliverWithFailureAfterEffect(final InboxDelivery delivery) {
        if (inbox.contains(delivery.eventKey())) {
            return;
        }
        // The transaction dies before commit. Because the effect is only ever applied AS
        // PART OF commit(), there is nothing to unwind — which is the property a real
        // consumer buys by sharing one transaction between effect and inbox insert.
        // A consumer that applied its effect here, before the commit, would leave it
        // behind and fail the kit.
    }

    /** The single atomic step: the effect and the inbox entry land together or not at all. */
    private void commit(final String eventKey) {
        effects.merge(eventKey, 1, Integer::sum);
        inbox.add(eventKey);
    }

    @Override
    public int effectCount(final String eventKey) {
        return effects.getOrDefault(eventKey, 0);
    }

    @Override
    public int totalEffectCount() {
        return effects.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Override
    public void reset() {
        inbox.clear();
        effects.clear();
    }
}
