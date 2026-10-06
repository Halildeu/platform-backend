package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordingIncompleteContractTest {
    @Test
    void rejectsMissingOrUnsafeMetadataAndUnknownReason() {
        for (String source : new String[] {null, "", "../audio", "SES-"}) {
            assertThatThrownBy(() -> build(source, MeetingEventGoldens.GENERATED_AT,
                    "CLOSURE_UNCONFIRMED", "meeting.recording", 1,
                    MeetingEventGoldens.RECORDING_SESSION_ID)).isInstanceOf(MeetingEventValidationException.class);
        }
        for (String reason : new String[] {null, "", "FINISHED", "free text"}) {
            assertThatThrownBy(() -> build("SES-42", MeetingEventGoldens.GENERATED_AT,
                    reason, "meeting.recording", 1, MeetingEventGoldens.RECORDING_SESSION_ID))
                    .isInstanceOf(MeetingEventValidationException.class);
        }
        assertThatThrownBy(() -> build("SES-42", null, "CLOSURE_UNCONFIRMED",
                "meeting.recording", 1, MeetingEventGoldens.RECORDING_SESSION_ID))
                .isInstanceOf(MeetingEventValidationException.class);
    }

    @Test
    void rejectsDifferentAggregateSessionAndRevision() {
        assertThatThrownBy(() -> build("SES-42", MeetingEventGoldens.GENERATED_AT,
                "CLOSURE_UNCONFIRMED", "meeting.transcript", 1, MeetingEventGoldens.RECORDING_SESSION_ID))
                .isInstanceOf(MeetingEventValidationException.class);
        assertThatThrownBy(() -> build("SES-42", MeetingEventGoldens.GENERATED_AT,
                "CLOSURE_UNCONFIRMED", "meeting.recording", 2, MeetingEventGoldens.RECORDING_SESSION_ID))
                .isInstanceOf(MeetingEventValidationException.class);
        assertThatThrownBy(() -> build("SES-42", MeetingEventGoldens.GENERATED_AT,
                "CLOSURE_UNCONFIRMED", "meeting.recording", 1, UUID.randomUUID()))
                .isInstanceOf(MeetingEventValidationException.class);
    }

    private MeetingEventEnvelope build(String source, Instant closed, String reason,
            String aggregate, long revision, UUID session) {
        return MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.RECORDING_INCOMPLETE).producer("meeting-service")
                .meetingId(MeetingEventGoldens.MEETING_ID).tenantId(MeetingEventGoldens.TENANT_ID)
                .orgId(MeetingEventGoldens.ORG_ID).occurredAt(MeetingEventGoldens.GENERATED_AT)
                .aggregateType(aggregate).aggregateId(session).aggregateRevision(revision)
                .payload(new MeetingEventPayload.RecordingIncomplete(
                        MeetingEventGoldens.RECORDING_SESSION_ID, source, closed, reason)).build();
    }
}
