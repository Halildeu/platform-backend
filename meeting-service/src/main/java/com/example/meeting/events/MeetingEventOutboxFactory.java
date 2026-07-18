package com.example.meeting.events;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventKeys;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventV1Serializer;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventType;
import com.example.meeting.model.MeetingSession;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

/**
 * Builds the transactional-outbox rows for a persisted analysis run — Faz 24
 * (platform-ai#244 BE-1d), migrated onto the shared contract in #802 slice 1.
 *
 * <h2>What this class is now</h2>
 * The adapter between meeting-service's JPA world and the shared contract, and nothing
 * more. It maps entities onto {@link MeetingEventEnvelope}s and lets
 * {@code common-meeting-events} own the envelope shape, the deterministic keys, the
 * validation and the v1 bytes; it then copies the result onto this service's OWN outbox
 * entity. The persistence half — the table, the migration, the poller — stays here on
 * purpose (#802 owner decision: no shared DB table, no central relay).
 *
 * <p>It is the SINGLE path from a run to an event: every emission goes through
 * {@link #build}, so a second envelope builder cannot quietly grow beside it. The two
 * rules that decide whether a fact exists at all stay here, because both are
 * meeting-service domain judgements rather than wire concerns:
 * <ul>
 *   <li><b>the summary gate</b> — a run with a null/blank summary has no summary to be
 *       ready, so it emits no {@code meeting.summary.ready};</li>
 *   <li><b>the attribution guard</b> — an action the model could not attribute emits no
 *       {@code meeting.action.assigned}. The shared validator enforces the same rule as
 *       a backstop: if one ever slipped through, it would be rejected here rather than
 *       published with an empty assignee.</li>
 * </ul>
 *
 * <h2>Wire compatibility</h2>
 * This migration changes NO byte and NO event key — see
 * {@code MeetingEventOutboxFactoryGoldenTest}, which asserts this service's output
 * against the golden fixtures captured from the pre-migration code.
 */
public final class MeetingEventOutboxFactory {

    /**
     * Which producer this is, stamped on every envelope.
     *
     * <p>Not on the v1 wire (those bytes are frozen) — it is envelope-level provenance
     * for the shared contract, and what a future v2 would render.
     */
    private static final String PRODUCER = "meeting-service";

    /** The aggregate these events are about: the analysis run. */
    private static final String AGGREGATE_TYPE = "meeting.analysis.run";

    /**
     * Occurrence counter for the aggregate — always 0 here, and that is a domain fact
     * rather than a stub: {@code analysisRunId} is minted per analysis, so a re-analysis
     * is a NEW run with a NEW id and therefore already a distinct event key. Producers
     * whose aggregate id is stable across repeats (consent revocation, transcript
     * re-finalization) are the ones that need a real revision.
     */
    private static final long AGGREGATE_REVISION = 0L;

    public MeetingEventOutboxFactory() {
    }

    /**
     * @param objectMapper unused since #802 — the v1 writer is owned by the shared
     *     serializer, so the wire format can no longer vary with a service's mapper
     *     config. The parameter stays for now to keep the constructor signature (and its
     *     single call site) unchanged; a later slice can drop it.
     */
    @SuppressWarnings("unused")
    public MeetingEventOutboxFactory(final ObjectMapper objectMapper) {
        this();
    }

    /**
     * Build every outbox row for a just-persisted run and its AI children. The caller
     * saves the returned rows in the SAME transaction as the run.
     */
    public List<MeetingEventOutbox> build(
            final MeetingAnalysisRun run,
            final List<MeetingDecision> decisions,
            final List<MeetingAction> actions) {

        final List<MeetingEventOutbox> rows = new ArrayList<>();

        if (hasText(run.getSummary())) {
            rows.add(row(run, MeetingEventType.SUMMARY_READY,
                    new MeetingEventPayload.SummaryReady(
                            run.getAnalysisRunId(),
                            run.getSummaryGroundingStatus(),
                            decisions.size(),
                            actions.size())));
        }

        for (MeetingAction action : actions) {
            // Attribution guard: only a real, non-blank assignee yields an event.
            if (hasText(action.getAssigneeSubject())) {
                rows.add(row(run, MeetingEventType.ACTION_ASSIGNED,
                        new MeetingEventPayload.ActionAssigned(
                                run.getAnalysisRunId(),
                                action.getOrdinal(),
                                action.getAssigneeSubject(),
                                action.getDueAt())));
            }
        }
        return rows;
    }

    /** Maps one shared envelope onto this service's own outbox entity. */
    private MeetingEventOutbox row(
            final MeetingAnalysisRun run,
            final MeetingEventType type,
            final MeetingEventPayload payload) {

        final MeetingEventEnvelope envelope = MeetingEventEnvelope.builder()
                .eventType(sharedType(type))
                .producer(PRODUCER)
                .meetingId(run.getMeetingId())
                .tenantId(run.getTenantId())
                .orgId(run.getOrgId())
                .occurredAt(run.getGeneratedAt())
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(run.getAnalysisRunId())
                .aggregateRevision(AGGREGATE_REVISION)
                .payload(payload)
                // eventKey is derived by the shared factory rather than restated here,
                // so this service cannot drift from the one deterministic derivation.
                .build();

        final MeetingEventOutbox outbox = new MeetingEventOutbox();
        outbox.setEventType(type.wireValue());
        outbox.setAggregateType(AGGREGATE_TYPE);
        outbox.setAggregateId(run.getAnalysisRunId());
        outbox.setAggregateRevision(AGGREGATE_REVISION);
        outbox.setMeetingId(run.getMeetingId());
        outbox.setTenantId(run.getTenantId());
        outbox.setOrgId(run.getOrgId());
        outbox.setEventKey(envelope.eventKey());
        final String payloadJson = MeetingEventV1Serializer.toJson(envelope);
        outbox.setPayload(payloadJson);
        outbox.setPayloadRaw(payloadJson);
        return outbox;
    }

    /** Build the metadata-only marker for the first FINISHED transition. */
    public MeetingEventOutbox buildRecordingFinished(
            final MeetingSession session,
            final Instant finishedAt) {
        final long revision = 1L;
        final String aggregateType = "meeting.recording";
        final MeetingEventPayload payload = new MeetingEventPayload.RecordingFinished(
                session.getId(), session.getExternalSessionId(), finishedAt);
        final MeetingEventEnvelope envelope = MeetingEventEnvelope.builder()
                .eventType(com.example.common.meeting.events.MeetingEventType.RECORDING_FINISHED)
                .producer(PRODUCER)
                .meetingId(session.getMeetingId())
                .tenantId(session.getTenantId())
                .orgId(session.getOrgId())
                .occurredAt(finishedAt)
                .aggregateType(aggregateType)
                .aggregateId(session.getId())
                .aggregateRevision(revision)
                .payload(payload)
                .build();

        final MeetingEventOutbox outbox = new MeetingEventOutbox();
        outbox.setEventType(MeetingEventType.RECORDING_FINISHED.wireValue());
        outbox.setAggregateType(aggregateType);
        outbox.setAggregateId(session.getId());
        outbox.setAggregateRevision(revision);
        outbox.setMeetingId(session.getMeetingId());
        outbox.setTenantId(session.getTenantId());
        outbox.setOrgId(session.getOrgId());
        outbox.setEventKey(envelope.eventKey());
        final String payloadJson = MeetingEventV1Serializer.toJson(envelope);
        outbox.setPayload(payloadJson);
        outbox.setPayloadRaw(payloadJson);
        return outbox;
    }

    /**
     * This service's local enum to the shared one.
     *
     * <p>The local {@code MeetingEventType} is what the DB CHECK constraint and the
     * entity speak, so the two coexist until a later slice retires the local copy. They
     * are joined on the wire value rather than the enum name, because the wire value is
     * the canonical thing both sides already agree on.
     */
    private static com.example.common.meeting.events.MeetingEventType sharedType(final MeetingEventType type) {
        return com.example.common.meeting.events.MeetingEventType.fromWire(type.wireValue());
    }

    // ────────────────────────── deterministic keys / guards ──────────────────────────

    /**
     * {@code <run>|meeting.summary.ready}.
     *
     * @deprecated Delegates to {@link MeetingEventKeys#summaryReadyKey} — call that directly.
     */
    @Deprecated(forRemoval = true)
    public static String summaryEventKey(final UUID analysisRunId) {
        return MeetingEventKeys.summaryReadyKey(analysisRunId);
    }

    /**
     * {@code <run>|meeting.action.assigned|<ordinal>}.
     *
     * @deprecated Delegates to {@link MeetingEventKeys#actionAssignedKey} — call that directly.
     */
    @Deprecated(forRemoval = true)
    public static String actionEventKey(final UUID analysisRunId, final int ordinal) {
        return MeetingEventKeys.actionAssignedKey(analysisRunId, ordinal);
    }

    /** The summary gate / attribution guard, exposed for the negative-proof test. */
    public static boolean hasText(final String value) {
        return MeetingEventKeys.hasText(value);
    }
}
