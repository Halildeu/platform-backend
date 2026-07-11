package com.example.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage of the outbox event-building rules — Faz 24 (platform-ai#244
 * BE-1d). No DB, no Spring: the factory's contract is deterministic keys, the LLM
 * attribution guard, the summary gate and a thin (text-free) payload.
 */
class MeetingEventOutboxFactoryTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeetingEventOutboxFactory factory = new MeetingEventOutboxFactory(objectMapper);

    private static final UUID RUN = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant GEN = Instant.parse("2026-07-11T10:00:00Z");

    @Test
    void summaryPresentAndOneAssignedAction_yieldSummaryReadyPlusOneActionAssigned() {
        MeetingAnalysisRun run = run("Toplantı özeti", "verified");
        MeetingAction assigned = action(0, "ali@example.com", Instant.parse("2026-07-20T09:00:00Z"));
        MeetingAction unassigned = action(1, null, null);

        List<MeetingEventOutbox> rows = factory.build(run, List.of(new MeetingDecision()), List.of(assigned, unassigned));

        assertThat(rows).hasSize(2);
        // summary.ready
        MeetingEventOutbox summary = rows.get(0);
        assertThat(summary.getEventType()).isEqualTo(MeetingEventType.SUMMARY_READY.wireValue());
        assertThat(summary.getEventKey()).isEqualTo(RUN + "|meeting.summary.ready");
        assertThat(summary.getAggregateId()).isEqualTo(RUN);
        assertThat(summary.getMeetingId()).isEqualTo(MEETING);
        assertThat(summary.getTenantId()).isEqualTo(TENANT);
        // action.assigned — ONLY for the non-null-assignee action, keyed by its ordinal.
        MeetingEventOutbox action = rows.get(1);
        assertThat(action.getEventType()).isEqualTo(MeetingEventType.ACTION_ASSIGNED.wireValue());
        assertThat(action.getEventKey()).isEqualTo(RUN + "|meeting.action.assigned|0");
    }

    @Test
    void nullOrBlankAssignee_producesNoActionAssignedEvent_attributionGuard() {
        MeetingAnalysisRun run = run("özet", "verified");
        List<MeetingEventOutbox> rows = factory.build(run, List.of(),
                List.of(action(0, null, null), action(1, "   ", null)));

        // Only the summary.ready row — neither the null nor the blank assignee emits.
        assertThat(rows).extracting(MeetingEventOutbox::getEventType)
                .containsExactly(MeetingEventType.SUMMARY_READY.wireValue());
    }

    @Test
    void nullOrBlankSummary_producesNoSummaryReadyEvent() {
        MeetingAnalysisRun nullSummary = run(null, null);
        assertThat(factory.build(nullSummary, List.of(), List.of(action(0, "a@x.io", null))))
                .extracting(MeetingEventOutbox::getEventType)
                .containsExactly(MeetingEventType.ACTION_ASSIGNED.wireValue());

        MeetingAnalysisRun blankSummary = run("   ", null);
        assertThat(factory.build(blankSummary, List.of(), List.of()))
                .isEmpty();
    }

    @Test
    void eventKeys_areDeterministic() {
        assertThat(MeetingEventOutboxFactory.summaryEventKey(RUN)).isEqualTo(RUN + "|meeting.summary.ready");
        assertThat(MeetingEventOutboxFactory.actionEventKey(RUN, 7)).isEqualTo(RUN + "|meeting.action.assigned|7");
        assertThat(MeetingEventOutboxFactory.hasText(null)).isFalse();
        assertThat(MeetingEventOutboxFactory.hasText(" ")).isFalse();
        assertThat(MeetingEventOutboxFactory.hasText("x")).isTrue();
    }

    @Test
    void payloadIsThin_carriesIdentifiersAndMetadataButNotSummaryText() throws Exception {
        String secret = "GİZLİ TOPLANTI İÇERİĞİ — should never be in the event";
        MeetingAnalysisRun run = run(secret, "verified");
        MeetingAction assigned = action(0, "ali@example.com", Instant.parse("2026-07-20T09:00:00Z"));

        List<MeetingEventOutbox> rows = factory.build(run, List.of(new MeetingDecision()), List.of(assigned));

        JsonNode summary = objectMapper.readTree(rows.get(0).getPayload());
        assertThat(summary.get("schema").asText()).isEqualTo("meeting.event.v1");
        assertThat(summary.get("analysisRunId").asText()).isEqualTo(RUN.toString());
        assertThat(summary.get("summaryGroundingStatus").asText()).isEqualTo("verified");
        assertThat(summary.get("decisionCount").asInt()).isEqualTo(1);
        assertThat(summary.get("actionCount").asInt()).isEqualTo(1);
        // Thin event: the summary TEXT is never embedded.
        assertThat(rows.get(0).getPayload()).doesNotContain(secret);

        JsonNode action = objectMapper.readTree(rows.get(1).getPayload());
        assertThat(action.get("ordinal").asInt()).isEqualTo(0);
        assertThat(action.get("assigneeSubject").asText()).isEqualTo("ali@example.com");
        assertThat(action.get("dueAt").asText()).isEqualTo("2026-07-20T09:00:00Z");
    }

    // ────────────────────────── helpers ──────────────────────────

    private static MeetingAnalysisRun run(String summary, String grounding) {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(RUN);
        run.setMeetingId(MEETING);
        run.setTenantId(TENANT);
        run.setOrgId(TENANT);
        run.setSummary(summary);
        run.setSummaryGroundingStatus(grounding);
        run.setGeneratedAt(GEN);
        return run;
    }

    private static MeetingAction action(int ordinal, String assignee, Instant due) {
        MeetingAction action = new MeetingAction();
        action.setMeetingId(MEETING);
        action.setTenantId(TENANT);
        action.setOrgId(TENANT);
        action.setDescription("do the thing");
        action.setAssigneeSubject(assignee);
        action.setOrdinal(ordinal);
        action.setDueAt(due);
        return action;
    }
}
