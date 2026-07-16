package com.example.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingEventOutbox;
import com.example.meeting.model.MeetingEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The #802 slice-1 migration moved no byte — first-PR acceptance 2 and 3.
 *
 * <p>The golden fixtures were captured from this factory BEFORE it delegated to
 * {@code common-meeting-events}. Asserting the post-migration output against them is
 * the actual proof that the refactor is wire-identical; the module's own golden test
 * only proves the module agrees with the same fixtures. Both sides, same bytes.
 *
 * <p>Byte equality rather than a parsed tree: field order and explicit nulls are what
 * production consumers receive, and the pre-migration test — which compared parsed
 * fields — would have passed while either moved.
 */
class MeetingEventOutboxFactoryGoldenTest {

    private final MeetingEventOutboxFactory factory = new MeetingEventOutboxFactory(new ObjectMapper());

    @Test
    void summaryAndActionRows_matchThePreMigrationGoldenBytesAndKeys() {
        MeetingAnalysisRun run = fixtureRun();
        MeetingAction action = fixtureAction(0, MeetingEventGoldens.ASSIGNEE, MeetingEventGoldens.DUE_AT);

        List<MeetingEventOutbox> rows = factory.build(run, List.of(new MeetingDecision()), List.of(action));

        assertThat(rows).hasSize(2);

        MeetingEventOutbox summary = rows.get(0);
        assertThat(summary.getPayload()).isEqualTo(MeetingEventGoldens.summaryReady());
        assertThat(summary.getEventKey()).isEqualTo(MeetingEventGoldens.summaryReadyKey());
        assertThat(summary.getEventType()).isEqualTo(MeetingEventType.SUMMARY_READY.wireValue());

        MeetingEventOutbox assigned = rows.get(1);
        assertThat(assigned.getPayload()).isEqualTo(MeetingEventGoldens.actionAssigned());
        assertThat(assigned.getEventKey()).isEqualTo(MeetingEventGoldens.actionAssignedKey());
        assertThat(assigned.getEventType()).isEqualTo(MeetingEventType.ACTION_ASSIGNED.wireValue());
    }

    @Test
    void nullableFieldsStillRenderAsExplicitNulls_afterTheMigration() {
        // The break a service-injected ObjectMapper could have caused silently: had the
        // shared serializer inherited a NON_NULL config, "orgId":null would have vanished
        // and every pinned consumer would see an absent field instead of a null one.
        MeetingAnalysisRun run = fixtureRun();
        run.setOrgId(null);
        run.setGeneratedAt(null);
        run.setSummaryGroundingStatus(null);
        run.setSummary("x");

        List<MeetingEventOutbox> rows = factory.build(run, List.of(),
                List.of(fixtureAction(MeetingEventGoldens.ORDINAL_NULL_HOLES,
                        MeetingEventGoldens.ASSIGNEE_NULL_HOLES, null)));

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).getPayload()).isEqualTo(MeetingEventGoldens.summaryReadyNullHoles());
        assertThat(rows.get(1).getPayload()).isEqualTo(MeetingEventGoldens.actionAssignedNullHoles());
    }

    @Test
    void outboxRowColumnsStillCarryTheirOwnIdentifiers_notOnlyThePayload() {
        // The row's columns are what this service's poller and UNIQUE index work on; the
        // payload is what ships. Both must stay populated after the migration.
        MeetingEventOutbox summary = factory.build(
                fixtureRun(), List.of(new MeetingDecision()),
                List.of(fixtureAction(0, MeetingEventGoldens.ASSIGNEE, MeetingEventGoldens.DUE_AT))).get(0);

        assertThat(summary.getAggregateId()).isEqualTo(MeetingEventGoldens.RUN_ID);
        assertThat(summary.getMeetingId()).isEqualTo(MeetingEventGoldens.MEETING_ID);
        assertThat(summary.getTenantId()).isEqualTo(MeetingEventGoldens.TENANT_ID);
        assertThat(summary.getOrgId()).isEqualTo(MeetingEventGoldens.ORG_ID);
    }

    @Test
    void payloadNeverCarriesTheSummaryText_redactionEndToEnd() {
        // The end-to-end half of first-PR acceptance 5: the module proves the wire has
        // nowhere to put meeting content; this proves the producer does not find a way.
        String secret = "GİZLİ TOPLANTI İÇERİĞİ — should never be in the event";
        MeetingAnalysisRun run = fixtureRun();
        run.setSummary(secret);

        List<MeetingEventOutbox> rows = factory.build(run, List.of(new MeetingDecision()),
                List.of(fixtureAction(0, MeetingEventGoldens.ASSIGNEE, MeetingEventGoldens.DUE_AT)));

        assertThat(rows).allSatisfy(row -> assertThat(row.getPayload()).doesNotContain(secret));
        // The summary row is byte-identical to the golden built from a harmless summary:
        // the text is not merely absent, it has no influence on the wire at all.
        assertThat(rows.get(0).getPayload()).isEqualTo(MeetingEventGoldens.summaryReady());
    }

    // ────────────────────────── fixtures ──────────────────────────

    private static MeetingAnalysisRun fixtureRun() {
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(MeetingEventGoldens.RUN_ID);
        run.setMeetingId(MeetingEventGoldens.MEETING_ID);
        run.setTenantId(MeetingEventGoldens.TENANT_ID);
        run.setOrgId(MeetingEventGoldens.ORG_ID);
        run.setSummary("Toplantı özeti");
        run.setSummaryGroundingStatus(MeetingEventGoldens.GROUNDING_STATUS);
        run.setGeneratedAt(MeetingEventGoldens.GENERATED_AT);
        return run;
    }

    private static MeetingAction fixtureAction(final int ordinal, final String assignee, final Instant dueAt) {
        MeetingAction action = new MeetingAction();
        action.setMeetingId(MeetingEventGoldens.MEETING_ID);
        action.setTenantId(MeetingEventGoldens.TENANT_ID);
        action.setOrgId(MeetingEventGoldens.ORG_ID);
        action.setDescription("do the thing");
        action.setAssigneeSubject(assignee);
        action.setOrdinal(ordinal);
        action.setDueAt(dueAt);
        return action;
    }
}
