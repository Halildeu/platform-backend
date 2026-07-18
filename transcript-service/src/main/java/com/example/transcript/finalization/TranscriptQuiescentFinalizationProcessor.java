package com.example.transcript.finalization;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventV1Serializer;
import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Atomically turns one due quiescence cycle into a ready or bounded failure event. */
@Service
public class TranscriptQuiescentFinalizationProcessor {

    static final String PRODUCER = "transcript-service";
    static final String AGGREGATE_TYPE = "meeting.transcript";
    static final String INVALID_CANONICAL_SEGMENT =
            MeetingEventPayload.TranscriptFailed.INVALID_CANONICAL_SEGMENT;

    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptSegmentRepository segments;
    private final TranscriptFinalizationRepository finalizations;
    private final TranscriptEventOutboxRepository outbox;
    private final TranscriptFinalizationStateMachine stateMachine;
    private final FinalizedTranscriptSnapshotCodec snapshotCodec;
    private final Clock clock;

    public TranscriptQuiescentFinalizationProcessor(
            TranscriptSessionAssociationRepository associations,
            TranscriptSegmentRepository segments,
            TranscriptFinalizationRepository finalizations,
            TranscriptEventOutboxRepository outbox,
            TranscriptFinalizationStateMachine stateMachine,
            FinalizedTranscriptSnapshotCodec snapshotCodec,
            Clock transcriptFinalizationClock) {
        this.associations = associations;
        this.segments = segments;
        this.finalizations = finalizations;
        this.outbox = outbox;
        this.stateMachine = stateMachine;
        this.snapshotCodec = snapshotCodec;
        this.clock = transcriptFinalizationClock;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Outcome process(UUID associationId) {
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        TranscriptSessionAssociation association = associations.findByIdForUpdate(associationId)
                .orElse(null);
        if (!isDue(association, now)) {
            return Outcome.NOT_DUE;
        }

        List<TranscriptSegment> canonical = segments.findCanonicalSessionForUpdate(
                association.getTenantId(), association.getMeetingId(), association.getSessionId());
        if (canonical.isEmpty()) {
            if (now.isBefore(association.getMaxWaitAt())) {
                association.setQuiescenceDueAt(association.getMaxWaitAt());
                association.setFinalizationErrorCode(null);
                associations.saveAndFlush(association);
                return Outcome.WAITING_FOR_CONTENT;
            }
            emitFailed(
                    association, now,
                    MeetingEventPayload.TranscriptFailed.NO_VALID_SEGMENTS_BEFORE_DEADLINE);
            return Outcome.FAILED;
        }

        FinalizedTranscriptSnapshotCodec.StoredSnapshot snapshot;
        try {
            snapshot = snapshotCodec.captureMachine(canonical);
        } catch (TranscriptSnapshotHasher.InvalidSnapshotException ex) {
            emitFailed(association, now, INVALID_CANONICAL_SEGMENT);
            return Outcome.INVALID_SNAPSHOT;
        }

        emitReady(association, snapshot, now);
        return Outcome.READY;
    }

    private boolean isDue(TranscriptSessionAssociation association, Instant now) {
        return association != null
                && association.getStatus() == TranscriptSessionAssociationStatus.RESOLVED
                && association.getSessionId() != null
                && association.getFinalizationState() == TranscriptFinalizationState.QUIESCING
                && association.getQuiescenceDueAt() != null
                && !association.getQuiescenceDueAt().isAfter(now);
    }

    private void emitReady(
            TranscriptSessionAssociation association,
            FinalizedTranscriptSnapshotCodec.StoredSnapshot snapshot,
            Instant now) {
        long cycle = requireCycle(association);
        UUID analysisRunId = UUID.randomUUID();
        MeetingEventPayload.TranscriptReady payload = new MeetingEventPayload.TranscriptReady(
                analysisRunId, association.getSessionId(), cycle, snapshot.segmentCount());
        MeetingEventEnvelope envelope = envelope(association, cycle, payload, now);
        String eventKey = envelope.eventKey();

        TranscriptFinalization row = new TranscriptFinalization();
        row.setId(deterministicId("finalization", eventKey));
        row.setTenantId(association.getTenantId());
        row.setOrgId(association.getTenantId());
        row.setMeetingId(association.getMeetingId());
        row.setSessionId(association.getSessionId());
        row.setFinalizationVersion(cycle);
        row.setAnalysisRunId(analysisRunId);
        row.setSegmentCount(snapshot.segmentCount());
        row.setSnapshotSha256(snapshot.sourceSnapshotSha256());
        row.setCanonicalTranscript(snapshot.transcript());
        row.setCanonicalTranscriptSha256(snapshot.transcriptSha256());
        row.setCanonicalSegments(snapshot.canonicalSegments());
        row.setCanonicalProjectionSha256(snapshot.canonicalProjectionSha256());
        row.setFinalizedAt(now);
        row.setCreatedAt(now);
        finalizations.save(row);
        outbox.save(outbox(envelope));
        stateMachine.markFinalized(association);
        associations.save(association);
        flush();
    }

    private void emitFailed(
            TranscriptSessionAssociation association, Instant now, String reason) {
        long cycle = requireCycle(association);
        MeetingEventPayload.TranscriptFailed payload = new MeetingEventPayload.TranscriptFailed(
                association.getSessionId(), cycle, reason);
        MeetingEventEnvelope envelope = envelope(association, cycle, payload, now);
        outbox.save(outbox(envelope));
        stateMachine.markTimedOut(association, reason);
        associations.save(association);
        outbox.flush();
        associations.flush();
    }

    private MeetingEventEnvelope envelope(
            TranscriptSessionAssociation association,
            long revision,
            MeetingEventPayload payload,
            Instant now) {
        return MeetingEventEnvelope.builder()
                .eventType(payload.eventType())
                .producer(PRODUCER)
                .meetingId(association.getMeetingId())
                .tenantId(association.getTenantId())
                .orgId(association.getTenantId())
                .occurredAt(now)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(association.getSessionId())
                .aggregateRevision(revision)
                .payload(payload)
                .build();
    }

    private TranscriptEventOutbox outbox(MeetingEventEnvelope envelope) {
        String eventKey = envelope.eventKey();
        TranscriptEventOutbox row = new TranscriptEventOutbox();
        row.setId(deterministicId("outbox", eventKey));
        row.setEventType(envelope.eventType().wireValue());
        row.setAggregateId(envelope.aggregateId());
        row.setMeetingId(envelope.meetingId());
        row.setTenantId(envelope.tenantId());
        row.setOrgId(envelope.tenantId());
        row.setPayload(MeetingEventV1Serializer.toJson(envelope));
        row.setEventKey(eventKey);
        return row;
    }

    private long requireCycle(TranscriptSessionAssociation association) {
        long cycle = association.getFinalizationCycleVersion();
        if (cycle < 1 || cycle <= association.getFinalizationVersion()) {
            throw new IllegalStateException("FINALIZATION_CYCLE_INVALID");
        }
        return cycle;
    }

    private UUID deterministicId(String kind, String eventKey) {
        return UUID.nameUUIDFromBytes((PRODUCER + "|" + kind + "|" + eventKey)
                .getBytes(StandardCharsets.UTF_8));
    }

    private void flush() {
        finalizations.flush();
        outbox.flush();
        associations.flush();
    }

    public enum Outcome {
        NOT_DUE,
        WAITING_FOR_CONTENT,
        READY,
        FAILED,
        INVALID_SNAPSHOT
    }
}
