package com.example.common.meeting.events;

import java.util.UUID;

/**
 * The deterministic event-key factory — #802 slice 1.
 *
 * <p>The event key is the idempotency identity of a fact. Every producer stores it
 * under a UNIQUE index in its own outbox, and every consumer de-duplicates on it, so
 * a retried or raced emission collapses to exactly-once EFFECT. Deterministic means:
 * same fact in, same key out, on any node, in any JVM, after any restart — no clock,
 * no random, no hash of a mutable payload.
 *
 * <h2>Why a key is never just {@code aggregateId + eventType}</h2>
 * Some facts legitimately RECUR for the same aggregate: consent revoked, granted and
 * revoked again; a transcript finalized, corrected, re-finalized. Keying those on
 * aggregate + type alone would make the second, genuine occurrence look like a
 * duplicate of the first and silently swallow it. Such producers therefore key on
 * their own occurrence counter — see {@link #occurrenceKey}.
 *
 * <h2>The v1 analysis events</h2>
 * {@link #summaryReadyKey} and {@link #actionAssignedKey} keep the exact legacy shape
 * (#802 first-PR acceptance 2: existing keys byte-identical). They need no revision
 * component, and that is a property of the domain rather than an omission:
 * {@code analysisRunId} is minted fresh per analysis, so a re-analysis of the same
 * meeting is a NEW run with a NEW id and therefore already a distinct key. The run id
 * IS the occurrence discriminator. Changing these keys is a v2 migration, not an edit.
 */
public final class MeetingEventKeys {

    /** Separator between key components. Frozen: it is part of every persisted key. */
    private static final String SEP = "|";

    private MeetingEventKeys() {
    }

    /**
     * {@code <run>|meeting.summary.ready} — frozen v1 shape.
     *
     * @param analysisRunId the run whose summary is ready; the occurrence discriminator
     */
    public static String summaryReadyKey(final UUID analysisRunId) {
        requireNotNull(analysisRunId, "analysisRunId");
        return analysisRunId + SEP + MeetingEventType.SUMMARY_READY.wireValue();
    }

    /**
     * {@code <run>|meeting.action.assigned|<ordinal>} — frozen v1 shape.
     *
     * <p>The ordinal is what separates the actions of ONE run from each other; without
     * it every action of a run would collapse onto a single key and only the first
     * would ever be delivered.
     *
     * @param analysisRunId the run the action belongs to
     * @param ordinal       the action's position within that run
     */
    public static String actionAssignedKey(final UUID analysisRunId, final int ordinal) {
        requireNotNull(analysisRunId, "analysisRunId");
        return analysisRunId + SEP + MeetingEventType.ACTION_ASSIGNED.wireValue() + SEP + ordinal;
    }

    /**
     * The general key for producers whose facts recur on a stable aggregate id —
     * {@code <aggregateType>|<aggregateId>|<eventType>|<revision>}.
     *
     * <p>Intended for the next slices (audio-gateway consent, transcript-service
     * finalization), where the aggregate id does NOT change between occurrences and the
     * producer-owned revision is the only thing that makes occurrence #2 distinct from
     * occurrence #1. The revision is REQUIRED and always rendered, including {@code 0}:
     * an "omit when zero" rule would make the first occurrence's key ambiguous with a
     * legacy-shaped key and quietly re-introduce the collapse this method exists to
     * prevent.
     *
     * @param aggregateType     the aggregate kind (e.g. {@code meeting.consent})
     * @param aggregateId       the aggregate whose fact this is
     * @param eventType         the fact
     * @param aggregateRevision producer-owned occurrence counter; must be &gt;= 0
     */
    public static String occurrenceKey(
            final String aggregateType,
            final UUID aggregateId,
            final MeetingEventType eventType,
            final long aggregateRevision) {

        requireText(aggregateType, "aggregateType");
        requireNotNull(aggregateId, "aggregateId");
        requireNotNull(eventType, "eventType");
        if (aggregateRevision < 0) {
            throw new MeetingEventValidationException(
                    "aggregateRevision must be >= 0 but was " + aggregateRevision);
        }
        return aggregateType + SEP + aggregateId + SEP + eventType.wireValue() + SEP + aggregateRevision;
    }

    /**
     * The key for a payload, dispatched on its type.
     *
     * <p>This is the single derivation every producer goes through: the switch is
     * exhaustive over the sealed payload, so a new event type cannot reach production
     * without an explicit decision about how it is keyed.
     */
    public static String forPayload(final MeetingEventPayload payload) {
        requireNotNull(payload, "payload");
        return switch (payload) {
            case MeetingEventPayload.SummaryReady p -> summaryReadyKey(p.analysisRunId());
            case MeetingEventPayload.ActionAssigned p -> actionAssignedKey(p.analysisRunId(), p.ordinal());
            case MeetingEventPayload.ConsentRevoked p -> occurrenceKey(
                    "meeting.consent",
                    p.captureId(),
                    MeetingEventType.CONSENT_REVOKED,
                    p.consentRevision());
            case MeetingEventPayload.TranscriptReady p -> occurrenceKey(
                    "meeting.transcript",
                    p.transcriptSessionId(),
                    MeetingEventType.TRANSCRIPT_READY,
                    p.finalizationVersion());
        };
    }

    /** The key for an envelope — {@link #forPayload} of its payload. */
    public static String forEnvelope(final MeetingEventEnvelope envelope) {
        requireNotNull(envelope, "envelope");
        return forPayload(envelope.payload());
    }

    /** True when the value is a real, non-blank string — the attribution guard. */
    public static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private static void requireNotNull(final Object value, final String field) {
        if (value == null) {
            throw new MeetingEventValidationException(field + " is required to build an event key.");
        }
    }

    private static void requireText(final String value, final String field) {
        if (!hasText(value)) {
            throw new MeetingEventValidationException(field + " is required to build an event key.");
        }
    }
}
