package com.example.common.meeting.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.common.meeting.events.conformance.MeetingEventGoldens;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Tenant/org isolation and payload redaction — #802 slice 1, first-PR acceptance 5. */
class MeetingEventIsolationAndRedactionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static final UUID OTHER_TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_ORG = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    // ────────────────────────── isolation ──────────────────────────

    @Test
    void everyEventCarriesItsTenantOnTheWire_soAConsumerCanFilter() throws Exception {
        for (String json : List.of(
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady()),
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.actionAssigned()))) {

            JsonNode node = mapper.readTree(json);
            assertThat(node.get("tenantId").asText()).isEqualTo(MeetingEventGoldens.TENANT_ID.toString());
            assertThat(node.get("orgId").asText()).isEqualTo(MeetingEventGoldens.ORG_ID.toString());
        }
    }

    @Test
    void tenantIsMandatory_anEventWithoutOneCannotBeBuilt() {
        // The tenant is the isolation boundary. An event that reached a broker without
        // one could not be filtered or routed, and a consumer would have to guess.
        assertThatThrownBy(() -> MeetingEventTestEnvelopes.base(MeetingEventType.ACTION_ASSIGNED)
                .tenantId(null)
                .payload(new MeetingEventPayload.ActionAssigned(
                        MeetingEventGoldens.RUN_ID, 0, MeetingEventGoldens.ASSIGNEE, null))
                .build())
                .isInstanceOf(MeetingEventValidationException.class)
                .hasMessageContaining("tenantId is required");
    }

    @Test
    void tenantAndOrgNeverLeakBetweenEnvelopes_noSharedMutableState() {
        // The builder is per-envelope; a static/shared payload map would make event #2
        // inherit event #1's tenant. Cheap to assert, catastrophic to miss.
        MeetingEventEnvelope tenantA = MeetingEventTestEnvelopes.summaryReady();
        MeetingEventEnvelope tenantB = MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .tenantId(OTHER_TENANT)
                .orgId(OTHER_ORG)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "verified", 1, 1))
                .build();

        assertThat(MeetingEventV1Serializer.toJson(tenantA))
                .contains(MeetingEventGoldens.TENANT_ID.toString())
                .doesNotContain(OTHER_TENANT.toString())
                .doesNotContain(OTHER_ORG.toString());
        assertThat(MeetingEventV1Serializer.toJson(tenantB))
                .contains(OTHER_TENANT.toString())
                .doesNotContain(MeetingEventGoldens.TENANT_ID.toString());
        // Re-render A after B: proves B's build did not mutate anything A depends on.
        assertThat(MeetingEventV1Serializer.toJson(tenantA))
                .isEqualTo(MeetingEventGoldens.summaryReady());
    }

    @Test
    void eventKeyIsTenantIndependent_documentedAndDeliberate() {
        // The v1 key is <analysisRunId>|<type>[|<ordinal>] — no tenant component, and it
        // cannot gain one without breaking acceptance 2. That is safe ONLY because
        // analysisRunId is a globally unique run primary key, never reused across
        // tenants: the run id already implies exactly one tenant. This test pins the
        // property so that if a future producer ever mints run ids per tenant, the
        // assumption fails loudly here rather than as one tenant's event being swallowed
        // as another's duplicate.
        MeetingEventEnvelope tenantA = MeetingEventTestEnvelopes.summaryReady();
        MeetingEventEnvelope tenantB = MeetingEventTestEnvelopes.base(MeetingEventType.SUMMARY_READY)
                .tenantId(OTHER_TENANT)
                .orgId(OTHER_ORG)
                .payload(new MeetingEventPayload.SummaryReady(MeetingEventGoldens.RUN_ID, "verified", 1, 1))
                .build();

        assertThat(tenantB.eventKey()).isEqualTo(tenantA.eventKey());
    }

    // ────────────────────────── redaction ──────────────────────────

    @Test
    void theV1FieldSetIsClosed_noFreeFormTextFieldExists() throws Exception {
        // Structural redaction: the strongest guarantee that summary/transcript text
        // never ships is that the wire has nowhere to put it. Pinning the exact field
        // set means a future field addition must be a deliberate, reviewed act.
        JsonNode summary = mapper.readTree(
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady()));
        assertThat(summary.fieldNames()).toIterable().containsExactly(
                "schema", "eventType", "analysisRunId", "meetingId", "tenantId",
                "orgId", "generatedAt", "summaryGroundingStatus", "decisionCount", "actionCount");

        JsonNode action = mapper.readTree(
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.actionAssigned()));
        assertThat(action.fieldNames()).toIterable().containsExactly(
                "schema", "eventType", "analysisRunId", "meetingId", "tenantId",
                "orgId", "generatedAt", "ordinal", "assigneeSubject", "dueAt");
    }

    @Test
    void payloadTypesHaveNoSummaryOrTranscriptTextComponent() {
        // The redaction guarantee restated at the type level: neither payload record has
        // a component that could carry meeting content, so a producer cannot pass it in
        // even by mistake. The end-to-end proof (a run whose summary text contains a
        // secret) lives in the producer's own test, where the text exists.
        assertThat(MeetingEventPayload.SummaryReady.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("analysisRunId", "summaryGroundingStatus", "decisionCount", "actionCount");

        assertThat(MeetingEventPayload.ActionAssigned.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("analysisRunId", "ordinal", "assigneeSubject", "dueAt");

        assertThat(MeetingEventPayload.RecordingFinished.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("recordingSessionId", "externalSessionId", "finishedAt");

        assertThat(MeetingEventPayload.TranscriptFailed.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("transcriptSessionId", "finalizationVersion", "reasonCode");
    }

    @Test
    void lifecycleEventsHaveClosedMetadataOnlyFieldSets() throws Exception {
        JsonNode finished = mapper.readTree(
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.recordingFinished()));
        assertThat(finished.fieldNames()).toIterable().containsExactly(
                "schema", "eventType", "analysisRunId", "meetingId", "tenantId",
                "orgId", "generatedAt", "recordingSessionId", "externalSessionId", "finishedAt");

        JsonNode failed = mapper.readTree(
                MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.transcriptFailed()));
        assertThat(failed.fieldNames()).toIterable().containsExactly(
                "schema", "eventType", "analysisRunId", "meetingId", "tenantId",
                "orgId", "generatedAt", "transcriptSessionId", "finalizationVersion", "reasonCode");

        for (JsonNode event : List.of(finished, failed)) {
            assertThat(event.toString())
                    .doesNotContain("audio", "text", "user", "recordingUri", "recordingURI", "uri");
        }
    }

    @Test
    void eventsAreThin_wireStaysSmallEnoughToBeSafeToLogAndReplay() {
        // A thin event is what lets an outbox row be retained, replayed and inspected
        // without becoming a second copy of the meeting's content.
        assertThat(MeetingEventV1Serializer.toJson(MeetingEventTestEnvelopes.summaryReady()))
                .hasSizeLessThan(1024);
    }
}
