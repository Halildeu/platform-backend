package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.example.common.meeting.events.RecordingOutcome;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestRequest;
import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRepository;
import com.example.meeting.security.AnalysisJobCapabilityVerifier;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

class MeetingAnalysisRecordingOutcomeTest {
    // Exercise both reconciliation branches explicitly, independently of DB race timing.
    @ParameterizedTest
    @CsvSource({"false,UNKNOWN", "true,UNKNOWN", "false,FINISHED", "true,FINISHED",
            "false,INCOMPLETE", "true,INCOMPLETE"})
    void retryMustKeepSignedClosureEvenWhenPayloadIsIdentical(boolean afterConflict, RecordingOutcome retryOutcome) {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Instant finalized = Instant.parse("2026-09-26T18:53:00Z");
        var meetings = mock(MeetingRepository.class);
        var runs = mock(MeetingAnalysisRunRepository.class);
        var tombstones = mock(MeetingAnalysisRunDestructionTombstoneRepository.class);
        var writer = mock(MeetingAnalysisResultWriter.class);
        var hasher = mock(MeetingAnalysisPayloadHasher.class);
        var verifier = mock(AnalysisJobCapabilityVerifier.class);
        var policy = mock(AnalysisGeneratedAtPolicy.class);
        var service = new MeetingAnalysisResultIngestionService(meetings, runs, tombstones,
                writer, hasher, verifier, policy);
        Meeting meeting = new Meeting();
        meeting.setTenantId(tenantId);
        when(meetings.findById(meetingId)).thenReturn(Optional.of(meeting));
        var request = new MeetingAnalysisResultIngestRequest(null, sessionId.toString(), "a".repeat(64),
                1L, finalized, "analysis-v1", "5-adr0043", "gpt-x", "openai", "p1",
                "summary", "verified", List.of(), List.of(), List.of(), 0, false, 0,
                finalized.plusSeconds(60), List.of(), List.of(), null);
        var binding = new AnalysisJobCapabilityVerifier.JobBinding(UUID.randomUUID(), tenantId,
                meetingId, sessionId, 1L, finalized, request.transcriptSha256(), runId,
                "analysis-v1", finalized.plusSeconds(300), retryOutcome,
                retryOutcome == RecordingOutcome.INCOMPLETE ? "CLOSURE_UNCONFIRMED" : null);
        when(verifier.verify("capability")).thenReturn(binding);
        when(hasher.hash(meetingId, tenantId, runId, request)).thenReturn("payload-hash");
        var stored = new MeetingAnalysisRun();
        stored.setAnalysisRunId(runId);
        stored.setMeetingId(meetingId);
        stored.setTenantId(tenantId);
        stored.setJobCapabilityId(UUID.randomUUID());
        stored.setPayloadHash("payload-hash");
        stored.setRecordingClosure(RecordingOutcome.INCOMPLETE, "CLOSURE_UNCONFIRMED");
        stored.setGeneratedAt(request.generatedAt());
        if (afterConflict) {
            when(runs.findById(runId)).thenReturn(Optional.empty());
            when(writer.insertNewRun(meeting, runId, "payload-hash", request, binding))
                    .thenThrow(new DataIntegrityViolationException("concurrent insert"));
            when(writer.findCommittedRun(runId)).thenReturn(Optional.of(stored));
        } else {
            when(runs.findById(runId)).thenReturn(Optional.of(stored));
        }

        if (retryOutcome == RecordingOutcome.INCOMPLETE) {
            assertThat(service.ingest(meetingId, runId, "capability", request).idempotentReplay()).isTrue();
            verify(writer).consumeRetryCapability(runId, binding);
        } else {
            assertThatThrownBy(() -> service.ingest(meetingId, runId, "capability", request))
                    .isInstanceOfSatisfying(ResponseStatusException.class, error -> {
                        assertThat(error.getStatusCode().value()).isEqualTo(409);
                        assertThat(error.getReason()).isEqualTo("IDEMPOTENCY_CONFLICT");
                    });
            verify(writer, never()).consumeRetryCapability(any(), any());
        }
        if (afterConflict) verify(writer).findCommittedRun(runId);
        else verify(writer, never()).insertNewRun(any(), any(), any(), any(), any());
        assertThat(stored.getRecordingOutcome()).isEqualTo(RecordingOutcome.INCOMPLETE);
    }
}
