package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** Unknown types and conditional required fields — #802 slice 1, first-PR acceptance 4. */
class MeetingEventValidatorTest {

    // ────────────────────────── unknown event type ──────────────────────────

    @Test
    void unknownWireValue_isRejectedFailClosed() {
        assertThatThrownBy(() -> MeetingEventType.fromWire("meeting.summary.leaked"))
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("Unknown meeting event type");
    }

    @Test
    void notYetShippedTypes_areUnknownUntilTheirProducerExists() {
        // Named in the #802 decision as later slices. Until a producer emits them with an
        // agreed payload, accepting the names would advertise a contract that does not
        // exist — so they are unknown ON PURPOSE, and this test is the reminder.
        assertThat(MeetingEventType.find("meeting.consent.revoked")).isEmpty();
        assertThat(MeetingEventType.find("meeting.transcript.ready")).isEmpty();
    }

    @Test
    void find_classifiesWithoutThrowing_soAConsumerCanDeadLetterInsteadOfCrashing() {
        // A consumer must be able to route an unhandleable event aside; if the only way
        // to ask "do I know this type?" threw, one poison row would stall the whole loop.
        assertThat(MeetingEventType.find("meeting.summary.ready"))
                .contains(MeetingEventType.SUMMARY_READY);
        assertThat(MeetingEventType.find("nonsense")).isEqualTo(Optional.empty());
        assertThat(MeetingEventType.find(null)).isEmpty();
    }

    @Test
    void knownTypesRoundTripThroughTheirWireValue() {
        for (MeetingEventType type : MeetingEventType.values()) {
            assertThat(MeetingEventType.fromWire(type.wireValue())).isEqualTo(type);
        }
    }

    // ────────────────────────── conditional required fields ──────────────────────────

    @Test
    void actionAssigned_requiresAssignee_theAttributionGuard() {
        // An action the model could not attribute must be dropped by the producer, never
        // emitted with an empty assignee.
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.ACTION_ASSIGNED)
                .payload(new MeetingEventPayload.ActionAssigned(MeetingEventGoldens.RUN_ID, 0, null, null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.assigneeSubject is required");

        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.ACTION_ASSIGNED)
                .payload(new MeetingEventPayload.ActionAssigned(MeetingEventGoldens.RUN_ID, 0, "   ", null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.assigneeSubject is required");
    }

    @Test
    void summaryReady_allowsNullGroundingStatus_conditionalNotGlobal() {
        // The mirror of the test above: "required" is per type. An ungrounded summary is
        // still a summary, so the same null that is fatal on an action is fine here.
        MeetingEventEnvelope envelope = MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, null, 0, 0))
                .build();

        assertThat(MeetingEventValidator.validationErrors(envelope)).isEmpty();
    }

    @Test
    void summaryReady_allowsNullOrgId_butNeverNullTenantId() {
        assertThat(MeetingEventValidator.validationErrors(
                MeetingEventTestEnvelopes.summaryReadyNullHoles())).isEmpty();

        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .tenantId(null)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "verified", 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("tenantId is required");
    }

    @Test
    void envelopeTypeMustMatchPayloadType() {
        // Otherwise the serializer would render an action's fields under the summary name.
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .payload(new MeetingEventPayload.ActionAssigned(
                        MeetingEventGoldens.RUN_ID, 0, "ali@example.com", null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("does not match payload type");
    }

    @Test
    void aggregateIdMustEqualPayloadAnalysisRunId() {
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .aggregateId(MeetingEventGoldens.MEETING_ID)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "verified", 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("must equal payload.analysisRunId");
    }

    @Test
    void negativeCountsAndOrdinalsAreRejected() {
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "v", -1, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.decisionCount must be >= 0");

        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.ACTION_ASSIGNED)
                .payload(new MeetingEventPayload.ActionAssigned(
                        MeetingEventGoldens.RUN_ID, -1, "ali@example.com", null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.ordinal must be >= 0");
    }

    @Test
    void producerAndAggregateTypeAreRequired() {
        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.SUMMARY_READY)
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateId(MeetingEventGoldens.RUN_ID)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "v", 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("producer is required")
                .hasMessageContaining("aggregateType is required");
    }

    @Test
    void everyViolationIsReportedAtOnce_notJustTheFirst() {
        // A producer fixing one field per CI run is a waste; the report is the whole set.
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .producer(null)
                .tenantId(null)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "v", 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("producer is required")
                .hasMessageContaining("tenantId is required");
    }

    @Test
    void nullEnvelopeIsReportedNotThrown() {
        assertThat(MeetingEventValidator.validationErrors(null)).containsExactly("envelope is null");
    }

    // ────────────────────────── event-key determinism ──────────────────────────

    @Test
    void callerSuppliedKeyThatDoesNotMatchTheDerivationIsRejected() {
        // A rehydrated envelope whose stored key drifted from the derivation would break
        // exactly-once de-duplication silently; surface it at build time instead.
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .eventKey("hand-rolled-key")
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "v", 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("is not the deterministic key");
    }

    @Test
    void occurrenceKey_alwaysRendersTheRevision_includingZero() {
        // Omitting a zero revision would make occurrence #0's key ambiguous with a
        // legacy-shaped key, re-introducing the collapse the revision exists to prevent.
        assertThat(MeetingEventKeys.occurrenceKey(
                "meeting.consent", MeetingEventGoldens.MEETING_ID, MeetingEventType.SUMMARY_READY, 0))
                .isEqualTo("meeting.consent|" + MeetingEventGoldens.MEETING_ID + "|meeting.summary.ready|0");

        assertThat(MeetingEventKeys.occurrenceKey(
                "meeting.consent", MeetingEventGoldens.MEETING_ID, MeetingEventType.SUMMARY_READY, 2))
                .isNotEqualTo(MeetingEventKeys.occurrenceKey(
                        "meeting.consent", MeetingEventGoldens.MEETING_ID, MeetingEventType.SUMMARY_READY, 1));
    }

    @Test
    void occurrenceKey_rejectsNegativeRevision() {
        assertThatThrownBy(() -> MeetingEventKeys.occurrenceKey(
                "meeting.consent", MeetingEventGoldens.MEETING_ID, MeetingEventType.SUMMARY_READY, -1))
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("aggregateRevision must be >= 0");
    }
}
