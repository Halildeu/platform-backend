package com.example.meeting.events;

import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the transactional-outbox rows for a persisted analysis run — Faz 24
 * (platform-ai#244 BE-1d). A deliberately plain (non-Spring) collaborator: the
 * writer constructs it with the shared {@link ObjectMapper}, and it is unit-tested
 * in isolation (no DB, no context).
 *
 * <h2>What it emits (the #412 events, Codex acceptance)</h2>
 * <ul>
 *   <li><b>meeting.summary.ready</b> — one row per run that actually HAS a summary.
 *       A run with a null/blank summary produces no "summary ready" event (there is
 *       no summary to be ready). Emitted only here, i.e. only once the run is being
 *       persisted — never before commit.</li>
 *   <li><b>meeting.action.assigned</b> — one row per AI action with a real,
 *       non-blank assignee (the LLM attribution guard). An action the model could
 *       not attribute (assignee null/blank) produces NO event.</li>
 * </ul>
 *
 * <p>{@code event_key} is deterministic ({@code <run>|<type>[|<ordinal>]}) so the
 * UNIQUE index makes a retried/raced ingestion idempotent (exactly-once).
 *
 * <p>Payload is thin + deterministic: identifiers, timestamps and small metadata
 * rendered as strings/ints so the JSON is identical under any {@code ObjectMapper}
 * config, and NEVER the summary/transcript text.
 */
public final class MeetingEventOutboxFactory {

    /** Event payload schema tag — lets a consumer pin the contract it parses. */
    static final String SCHEMA = "meeting.event.v1";

    private final ObjectMapper objectMapper;

    public MeetingEventOutboxFactory(final ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Build every outbox row for a just-persisted run and its AI children. The
     * caller saves the returned rows in the SAME transaction as the run.
     */
    public List<MeetingEventOutbox> build(
            final MeetingAnalysisRun run,
            final List<MeetingDecision> decisions,
            final List<MeetingAction> actions) {

        final List<MeetingEventOutbox> rows = new ArrayList<>();

        if (hasText(run.getSummary())) {
            rows.add(summaryReady(run, decisions.size(), actions.size()));
        }

        for (MeetingAction action : actions) {
            // Attribution guard: only a real, non-blank assignee yields an event.
            if (hasText(action.getAssigneeSubject())) {
                rows.add(actionAssigned(run, action));
            }
        }
        return rows;
    }

    private MeetingEventOutbox summaryReady(
            final MeetingAnalysisRun run, final int decisionCount, final int actionCount) {

        final Map<String, Object> payload = basePayload(run, MeetingEventType.SUMMARY_READY);
        payload.put("summaryGroundingStatus", run.getSummaryGroundingStatus());
        payload.put("decisionCount", decisionCount);
        payload.put("actionCount", actionCount);

        return row(run, MeetingEventType.SUMMARY_READY,
                summaryEventKey(run.getAnalysisRunId()), payload);
    }

    private MeetingEventOutbox actionAssigned(final MeetingAnalysisRun run, final MeetingAction action) {
        final Map<String, Object> payload = basePayload(run, MeetingEventType.ACTION_ASSIGNED);
        payload.put("ordinal", action.getOrdinal());
        payload.put("assigneeSubject", action.getAssigneeSubject());
        payload.put("dueAt", action.getDueAt() == null ? null : action.getDueAt().toString());

        return row(run, MeetingEventType.ACTION_ASSIGNED,
                actionEventKey(run.getAnalysisRunId(), action.getOrdinal()), payload);
    }

    private Map<String, Object> basePayload(final MeetingAnalysisRun run, final MeetingEventType type) {
        final Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("schema", SCHEMA);
        payload.put("eventType", type.wireValue());
        payload.put("analysisRunId", run.getAnalysisRunId().toString());
        payload.put("meetingId", run.getMeetingId().toString());
        payload.put("tenantId", run.getTenantId().toString());
        payload.put("orgId", run.getOrgId() == null ? null : run.getOrgId().toString());
        final Instant generatedAt = run.getGeneratedAt();
        payload.put("generatedAt", generatedAt == null ? null : generatedAt.toString());
        return payload;
    }

    private MeetingEventOutbox row(
            final MeetingAnalysisRun run,
            final MeetingEventType type,
            final String eventKey,
            final Map<String, Object> payload) {

        final MeetingEventOutbox outbox = new MeetingEventOutbox();
        outbox.setEventType(type.wireValue());
        outbox.setAggregateId(run.getAnalysisRunId());
        outbox.setMeetingId(run.getMeetingId());
        outbox.setTenantId(run.getTenantId());
        outbox.setOrgId(run.getOrgId());
        outbox.setEventKey(eventKey);
        outbox.setPayload(toJson(payload));
        return outbox;
    }

    // ────────────────────────── deterministic keys / guards ──────────────────────────

    /** {@code <run>|meeting.summary.ready}. */
    public static String summaryEventKey(final UUID analysisRunId) {
        return analysisRunId + "|" + MeetingEventType.SUMMARY_READY.wireValue();
    }

    /** {@code <run>|meeting.action.assigned|<ordinal>}. */
    public static String actionEventKey(final UUID analysisRunId, final int ordinal) {
        return analysisRunId + "|" + MeetingEventType.ACTION_ASSIGNED.wireValue() + "|" + ordinal;
    }

    /** The attribution guard, exposed for the negative-proof test. */
    public static boolean hasText(final String value) {
        return value != null && !value.isBlank();
    }

    private String toJson(final Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise meeting event payload.", e);
        }
    }
}
