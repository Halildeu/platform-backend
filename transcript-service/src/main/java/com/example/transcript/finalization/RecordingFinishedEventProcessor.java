package com.example.transcript.finalization;

import com.example.transcript.directstt.DirectSttTranscriptResultEvent;
import com.example.transcript.model.TranscriptMeetingEventInbox;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptMeetingEventInboxRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.service.SessionErasureFence;
import com.example.transcript.service.SessionErasureFence.UUIDScope;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Atomically deduplicates, binds and enrolls one recording-finished occurrence. */
@Service
public class RecordingFinishedEventProcessor {

    private final TranscriptMeetingEventInboxRepository inbox;
    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptFinalizationStateMachine stateMachine;
    private final SessionErasureFence erasureFence;
    private final Clock clock;

    public RecordingFinishedEventProcessor(
            TranscriptMeetingEventInboxRepository inbox,
            TranscriptSessionAssociationRepository associations,
            TranscriptFinalizationStateMachine stateMachine,
            SessionErasureFence erasureFence,
            Clock transcriptFinalizationClock) {
        this.inbox = inbox;
        this.associations = associations;
        this.stateMachine = stateMachine;
        this.erasureFence = erasureFence;
        this.clock = transcriptFinalizationClock;
    }

    @Transactional
    public ProcessResult process(RecordingFinishedEvent event) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        UUIDScope scope = new UUIDScope(event.tenantId(), event.meetingId(), event.recordingSessionId());
        erasureFence.lock(
                SessionErasureFence.canonicalKey(scope),
                SessionErasureFence.sourceKey(
                        event.tenantId(), event.meetingId(), event.externalSessionId()));
        erasureFence.rejectErased(scope, event.externalSessionId());
        int inserted = inbox.insertIfAbsent(
                deterministicId("inbox", event.eventKey()), event.eventKey(),
                RecordingFinishedEventParser.EVENT_TYPE, event.payloadSha256(),
                event.tenantId(), event.meetingId(), event.recordingSessionId(),
                event.externalSessionId(), now);

        TranscriptMeetingEventInbox stored = inbox.findByEventKey(event.eventKey())
                .orElseThrow(() -> new IllegalStateException("INBOX_INSERT_MISSING"));
        verifyInboxScope(stored, event);
        if (inserted == 0 && stored.getProcessedAt() != null) {
            return ProcessResult.DUPLICATE;
        }

        associations.insertResolvedIfAbsent(
                deterministicId("association", event.eventKey()), event.tenantId(),
                event.meetingId(), DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                event.externalSessionId(), event.recordingSessionId(), now);
        TranscriptSessionAssociation association = associations.findSourceForUpdate(
                        event.tenantId(), event.meetingId(),
                        DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                        event.externalSessionId())
                .orElseThrow(() -> new IllegalStateException("ASSOCIATION_INSERT_MISSING"));
        if (association.getSessionId() == null) {
            if (associations.bindResolvedFromFinishedEvent(
                    association.getId(), event.recordingSessionId(), now) != 1) {
                throw new RecordingFinishedEventConflictException("ASSOCIATION_BIND_RACE");
            }
            association = associations.findSourceForUpdate(
                            event.tenantId(), event.meetingId(),
                            DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                            event.externalSessionId())
                    .orElseThrow(() -> new IllegalStateException("ASSOCIATION_BIND_MISSING"));
        }
        verifyAssociationScope(association, event);
        stateMachine.observeRecordingFinished(association, event.finishedAt(), now);
        associations.saveAndFlush(association);
        if (inbox.markProcessed(event.eventKey(), now) != 1) {
            throw new RecordingFinishedEventConflictException("INBOX_PROCESS_RACE");
        }
        return ProcessResult.PROCESSED;
    }

    private void verifyInboxScope(
            TranscriptMeetingEventInbox stored, RecordingFinishedEvent event) {
        if (!stored.getPayloadSha256().equals(event.payloadSha256())
                || !stored.getTenantId().equals(event.tenantId())
                || !stored.getMeetingId().equals(event.meetingId())
                || !stored.getSessionId().equals(event.recordingSessionId())
                || !stored.getSourceSessionId().equals(event.externalSessionId())) {
            throw new RecordingFinishedEventConflictException("INBOX_KEY_DIVERGENCE");
        }
    }

    private void verifyAssociationScope(
            TranscriptSessionAssociation association, RecordingFinishedEvent event) {
        if (association.getStatus() != TranscriptSessionAssociationStatus.RESOLVED
                || !association.getTenantId().equals(event.tenantId())
                || !association.getMeetingId().equals(event.meetingId())
                || !DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(association.getSourceSystem())
                || !association.getSourceSessionId().equals(event.externalSessionId())
                || !event.recordingSessionId().equals(association.getSessionId())) {
            throw new RecordingFinishedEventConflictException("ASSOCIATION_SCOPE_DIVERGENCE");
        }
    }

    private UUID deterministicId(String kind, String eventKey) {
        return UUID.nameUUIDFromBytes(("transcript-service|" + kind + "|" + eventKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    public enum ProcessResult { PROCESSED, DUPLICATE }

    public static class RecordingFinishedEventConflictException extends IllegalStateException {
        public RecordingFinishedEventConflictException(String reason) { super(reason); }
    }
}
