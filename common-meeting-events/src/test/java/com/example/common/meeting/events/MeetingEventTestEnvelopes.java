package com.example.common.meeting.events;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;

/** The envelopes behind the golden fixtures, built from {@link MeetingEventGoldens}' inputs. */
final class MeetingEventTestEnvelopes {

    static final String PRODUCER = "meeting-service";
    static final String AGGREGATE_TYPE = "meeting.analysis.run";

    private MeetingEventTestEnvelopes() {
    }

    static MeetingEventEnvelope.Builder base(final MeetingEventType type) {
        return MeetingEventEnvelope.builder()
                .eventType(type)
                .producer(PRODUCER)
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .orgId(MeetingEventGoldens.ORG_ID)
                .occurredAt(MeetingEventGoldens.GENERATED_AT)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(MeetingEventGoldens.RUN_ID)
                .aggregateRevision(0);
    }

    static MeetingEventEnvelope summaryReady() {
        return base(MeetingEventType.SUMMARY_READY)
                .payload(new MeetingEventPayload.SummaryReady(
                        MeetingEventGoldens.RUN_ID, MeetingEventGoldens.GROUNDING_STATUS, 1, 1))
                .build();
    }

    static MeetingEventEnvelope actionAssigned() {
        return base(MeetingEventType.ACTION_ASSIGNED)
                .payload(new MeetingEventPayload.ActionAssigned(
                        MeetingEventGoldens.RUN_ID, 0, MeetingEventGoldens.ASSIGNEE, MeetingEventGoldens.DUE_AT))
                .build();
    }

    static MeetingEventEnvelope summaryReadyNullHoles() {
        return base(MeetingEventType.SUMMARY_READY)
                .orgId(null)
                .occurredAt(null)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, null, 0, 1))
                .build();
    }

    static MeetingEventEnvelope actionAssignedNullHoles() {
        return base(MeetingEventType.ACTION_ASSIGNED)
                .orgId(null)
                .occurredAt(null)
                .payload(new MeetingEventPayload.ActionAssigned(
                        MeetingEventGoldens.RUN_ID,
                        MeetingEventGoldens.ORDINAL_NULL_HOLES,
                        MeetingEventGoldens.ASSIGNEE_NULL_HOLES,
                        null))
                .build();
    }
}
