package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import org.junit.jupiter.api.Test;

/**
 * The v1 wire is frozen — #802 slice 1, first-PR acceptance 2.
 *
 * <p>Byte equality, not a parsed-tree comparison: field ORDER and explicit nulls are
 * part of what production consumers receive today, and a {@code readTree} assertion
 * (what the pre-refactor test did) would pass happily while both moved.
 */
class MeetingEventV1GoldenTest {

    @Test
    void summaryReady_rendersTheGoldenBytes() {
        assertThat(MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady()))
                .isEqualTo(MeetingEventGoldens.summaryReady());
    }

    @Test
    void actionAssigned_rendersTheGoldenBytes() {
        assertThat(MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.actionAssigned()))
                .isEqualTo(MeetingEventGoldens.actionAssigned());
    }

    @Test
    void transcriptReady_rendersTheGoldenBytes() {
        assertThat(MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.transcriptReady()))
                .isEqualTo(MeetingEventGoldens.transcriptReady());
    }

    @Test
    void summaryReady_nullableFieldsRenderAsExplicitNulls_notOmitted() {
        String json = MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReadyNullHoles());

        assertThat(json).isEqualTo(MeetingEventGoldens.summaryReadyNullHoles());
        // Stated separately so the intent survives a future golden re-capture.
        assertThat(json).contains("\"orgId\":null", "\"generatedAt\":null", "\"summaryGroundingStatus\":null");
    }

    @Test
    void actionAssigned_nullableFieldsRenderAsExplicitNulls_notOmitted() {
        String json = MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.actionAssignedNullHoles());

        assertThat(json).isEqualTo(MeetingEventGoldens.actionAssignedNullHoles());
        assertThat(json).contains("\"orgId\":null", "\"generatedAt\":null", "\"dueAt\":null");
    }

    @Test
    void eventKeys_matchTheFrozenV1Shape() {
        assertThat(MeetingEventTestEnvelopes.summaryReady().eventKey())
                .isEqualTo(MeetingEventGoldens.summaryReadyKey())
                .isEqualTo(MeetingEventGoldens.RUN_ID + "|meeting.summary.ready");
        assertThat(MeetingEventTestEnvelopes.actionAssigned().eventKey())
                .isEqualTo(MeetingEventGoldens.actionAssignedKey())
                .isEqualTo(MeetingEventGoldens.RUN_ID + "|meeting.action.assigned|0");
        assertThat(MeetingEventTestEnvelopes.transcriptReady().eventKey())
                .isEqualTo("meeting.transcript|" + MeetingEventGoldens.TRANSCRIPT_SESSION_ID
                        + "|meeting.transcript.ready|1");
    }

    @Test
    void serializationIsStable_sameEnvelopeAlwaysSameBytes() {
        // Determinism is the premise the outbox's UNIQUE index and every consumer's
        // de-duplication rest on; a LinkedHashMap swapped for a HashMap would break it.
        String first = MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady());
        for (int i = 0; i < 50; i++) {
            assertThat(MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady()))
                    .isEqualTo(first);
        }
    }

    @Test
    void schemaTagIsPinned() {
        assertThat(MeetingEventV1Serializer.SCHEMA).isEqualTo("meeting.event.v1");
    }
}
