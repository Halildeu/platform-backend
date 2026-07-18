package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import java.time.Instant;
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
    void shippedProducerTypes_areKnown() {
        assertThat(MeetingEventType.find("meeting.consent.revoked"))
                .contains(MeetingEventType.CONSENT_REVOKED);
        assertThat(MeetingEventType.find("meeting.transcript.ready"))
                .contains(MeetingEventType.TRANSCRIPT_READY);
        assertThat(MeetingEventType.find("meeting.recording.finished"))
                .contains(MeetingEventType.RECORDING_FINISHED);
        assertThat(MeetingEventType.find("meeting.transcript.failed"))
                .contains(MeetingEventType.TRANSCRIPT_FAILED);
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

    @Test
    void recordingFinished_requiresCanonicalScopeRevisionAndMetadata() {
        assertThat(MeetingEventValidator.validationErrors(
                MeetingEventTestEnvelopes.recordingFinished())).isEmpty();

        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.RECORDING_FINISHED)
                .producer("meeting-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.transcript")
                .aggregateId(MeetingEventGoldens.RECORDING_SESSION_ID)
                .aggregateRevision(2)
                .payload(new MeetingEventPayload.RecordingFinished(
                        MeetingEventGoldens.RECORDING_SESSION_ID, "../unsafe", null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.externalSessionId has invalid format")
                .hasMessageContaining("payload.finishedAt is required")
                .hasMessageContaining("aggregateRevision 2")
                .hasMessageContaining("aggregateType must be meeting.recording");

        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.RECORDING_FINISHED)
                .producer("meeting-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.recording")
                .aggregateId(MeetingEventGoldens.RECORDING_SESSION_ID)
                .aggregateRevision(1)
                .payload(new MeetingEventPayload.RecordingFinished(
                        MeetingEventGoldens.RECORDING_SESSION_ID,
                        "room-1", Instant.parse("2026-07-17T10:05:00Z")))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.externalSessionId has invalid format");
    }

    @Test
    void transcriptFailed_acceptsOnlyTheTwoBoundedRecoveryReasons() {
        assertThat(MeetingEventValidator.validationErrors(
                MeetingEventTestEnvelopes.transcriptFailed())).isEmpty();
        assertThat(MeetingEventValidator.validationErrors(
                MeetingEventTestEnvelopes.transcriptFailedInvalidCanonicalSegment())).isEmpty();

        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.TRANSCRIPT_FAILED)
                .producer("transcript-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.transcript")
                .aggregateId(MeetingEventGoldens.TRANSCRIPT_SESSION_ID)
                .aggregateRevision(1)
                .payload(new MeetingEventPayload.TranscriptFailed(
                        MeetingEventGoldens.TRANSCRIPT_SESSION_ID, 1, "free text"))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.reasonCode must be one of")
                .hasMessageContaining("NO_VALID_SEGMENTS_BEFORE_DEADLINE")
                .hasMessageContaining("INVALID_CANONICAL_SEGMENT");
    }

    @Test
    void consentRevoked_hasDeterministicOccurrenceKeyAndFrozenThinWire() {
        var captureId = java.util.UUID.fromString("33333333-3333-4333-8333-333333333333");
        var envelope = MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.CONSENT_REVOKED)
                .producer("audit-event-consumer-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .orgId(MeetingEventGoldens.ORG_ID)
                .occurredAt(MeetingEventGoldens.GENERATED_AT)
                .aggregateType("meeting.consent")
                .aggregateId(captureId)
                .aggregateRevision(2)
                .payload(new MeetingEventPayload.ConsentRevoked(
                        captureId, "recorder-consent-v1", 2, "USER_WITHDREW"))
                .build();

        assertThat(envelope.eventKey()).isEqualTo(
                "meeting.consent|" + captureId + "|meeting.consent.revoked|2");
        assertThat(MeetingEventV1Serializer.toJson(envelope)).isEqualTo(
                "{\"schema\":\"meeting.event.v1\","
                        + "\"eventType\":\"meeting.consent.revoked\","
                        + "\"analysisRunId\":null,"
                        + "\"meetingId\":\"22222222-2222-2222-2222-222222222222\","
                        + "\"tenantId\":\"33333333-3333-3333-3333-333333333333\","
                        + "\"orgId\":\"44444444-4444-4444-4444-444444444444\","
                        + "\"generatedAt\":\"2026-07-11T10:00:00Z\","
                        + "\"captureId\":\"33333333-3333-4333-8333-333333333333\","
                        + "\"consentVersion\":\"recorder-consent-v1\","
                        + "\"consentRevision\":2,"
                        + "\"reasonCode\":\"USER_WITHDREW\"}");
    }

    @Test
    void consentRevoked_rejectsZeroRevisionAndUnboundedReasonText() {
        var captureId = java.util.UUID.fromString("33333333-3333-4333-8333-333333333333");

        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.CONSENT_REVOKED)
                .producer("audit-event-consumer-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.consent")
                .aggregateId(captureId)
                .aggregateRevision(0)
                .payload(new MeetingEventPayload.ConsentRevoked(
                        captureId, "v1", 0, "USER_WITHDREW"))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("consentRevision must be >= 1");

        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.CONSENT_REVOKED)
                .producer("audit-event-consumer-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.consent")
                .aggregateId(captureId)
                .aggregateRevision(2)
                .payload(new MeetingEventPayload.ConsentRevoked(
                        captureId, "v1", 2, "PERSONAL_DETAILS"))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("reasonCode must be USER_WITHDREW");
    }


    @Test
    void transcriptReady_requiresCanonicalSessionPositiveVersionAndSegments() {
        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.TRANSCRIPT_READY)
                .producer("transcript-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.transcript")
                .aggregateId(MeetingEventGoldens.TRANSCRIPT_SESSION_ID)
                .aggregateRevision(0)
                .payload(new MeetingEventPayload.TranscriptReady(
                        MeetingEventGoldens.RUN_ID,
                        MeetingEventGoldens.TRANSCRIPT_SESSION_ID, 0, 0))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("finalizationVersion must be >= 1")
                .hasMessageContaining("segmentCount must be >= 1");
    }

    @Test
    void transcriptReady_requiresProducerMintedAnalysisRunIdentity() {
        assertThatThrownBy(() -> MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.TRANSCRIPT_READY)
                .producer("transcript-service")
                .meetingId(MeetingEventGoldens.MEETING_ID)
                .tenantId(MeetingEventGoldens.TENANT_ID)
                .aggregateType("meeting.transcript")
                .aggregateId(MeetingEventGoldens.TRANSCRIPT_SESSION_ID)
                .aggregateRevision(1)
                .payload(new MeetingEventPayload.TranscriptReady(
                        null, MeetingEventGoldens.TRANSCRIPT_SESSION_ID, 1, 1))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("payload.analysisRunId is required");
    }

    @Test
    void transcriptReady_isThinAndCarriesNoTranscriptOrAudioContent() {
        String json = MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.transcriptReady());

        assertThat(json)
                .contains("\"transcriptSessionId\"")
                .contains("\"finalizationVersion\":1")
                .doesNotContain("text", "audio", "speaker", "recordingUri", "sha256");
    }
}
