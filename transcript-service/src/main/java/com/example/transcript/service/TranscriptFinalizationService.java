package com.example.transcript.service;

import com.example.common.meeting.events.MeetingEventEnvelope;
import com.example.common.meeting.events.MeetingEventPayload;
import com.example.common.meeting.events.MeetingEventType;
import com.example.common.meeting.events.MeetingEventV1Serializer;
import com.example.transcript.dto.TranscriptFinalizationDto;
import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.security.AdminTenantContext;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Defines and atomically records explicit canonical transcript finalization. */
@Service
public class TranscriptFinalizationService {

    static final String AGGREGATE_TYPE = "meeting.transcript";
    static final String PRODUCER = "transcript-service";

    private final TranscriptSessionAssociationRepository associationRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptFinalizationRepository finalizationRepository;
    private final TranscriptEventOutboxRepository outboxRepository;
    private final Clock clock;

    @Autowired
    public TranscriptFinalizationService(
            TranscriptSessionAssociationRepository associationRepository,
            TranscriptSegmentRepository segmentRepository,
            TranscriptFinalizationRepository finalizationRepository,
            TranscriptEventOutboxRepository outboxRepository) {
        this(associationRepository, segmentRepository, finalizationRepository,
                outboxRepository, Clock.systemUTC());
    }

    TranscriptFinalizationService(
            TranscriptSessionAssociationRepository associationRepository,
            TranscriptSegmentRepository segmentRepository,
            TranscriptFinalizationRepository finalizationRepository,
            TranscriptEventOutboxRepository outboxRepository,
            Clock clock) {
        this.associationRepository = associationRepository;
        this.segmentRepository = segmentRepository;
        this.finalizationRepository = finalizationRepository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    /**
     * Locks the canonical association before the segment set. Direct-STT ingestion
     * takes the same first lock, so a finalization snapshot cannot race a new chunk.
     */
    @Transactional
    public TranscriptFinalizationDto finalizeTranscript(
            AdminTenantContext context,
            UUID meetingId,
            UUID sessionId,
            long requestedVersion) {
        if (requestedVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "finalizationVersion must be at least 1.");
        }

        UUID tenantId = context.tenantId();
        TranscriptSessionAssociation association = associationRepository
                .findCanonicalForUpdate(tenantId, meetingId, sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Canonical transcript session not found."));

        long currentVersion = association.getFinalizationVersion();
        if (requestedVersion == currentVersion) {
            TranscriptFinalization existing = finalizationRepository
                    .findByTenantIdAndMeetingIdAndSessionIdAndFinalizationVersion(
                            tenantId, meetingId, sessionId, requestedVersion)
                    .orElseThrow(() -> new IllegalStateException(
                            "Canonical finalization version has no immutable occurrence row."));
            List<TranscriptSegment> currentSegments = segmentRepository
                    .findCanonicalSessionForUpdate(tenantId, meetingId, sessionId);
            validateFinalSegments(currentSegments);
            if (existing.getSegmentCount() != currentSegments.size()
                    || !existing.getSnapshotSha256().equals(snapshotSha256(currentSegments))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Canonical transcript changed; use the next finalizationVersion.");
            }
            return toDto(existing);
        }
        if (requestedVersion != currentVersion + 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "finalizationVersion must be exactly " + (currentVersion + 1) + ".");
        }

        List<TranscriptSegment> segments = segmentRepository.findCanonicalSessionForUpdate(
                tenantId, meetingId, sessionId);
        validateFinalSegments(segments);

        // PostgreSQL timestamptz persists microseconds. Normalize before both the
        // immutable row and event payload are created so replayed DTOs and exact
        // serialized bytes cannot diverge after a database round trip.
        Instant finalizedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        MeetingEventPayload.TranscriptReady payload = new MeetingEventPayload.TranscriptReady(
                sessionId, requestedVersion, segments.size());
        MeetingEventEnvelope envelope = MeetingEventEnvelope.builder()
                .eventType(MeetingEventType.TRANSCRIPT_READY)
                .producer(PRODUCER)
                .meetingId(meetingId)
                .tenantId(tenantId)
                .orgId(tenantId)
                .occurredAt(finalizedAt)
                .aggregateType(AGGREGATE_TYPE)
                .aggregateId(sessionId)
                .aggregateRevision(requestedVersion)
                .payload(payload)
                .build();

        String eventKey = envelope.eventKey();
        TranscriptFinalization finalization = new TranscriptFinalization();
        finalization.setId(deterministicId("finalization", eventKey));
        finalization.setTenantId(tenantId);
        finalization.setOrgId(tenantId);
        finalization.setMeetingId(meetingId);
        finalization.setSessionId(sessionId);
        finalization.setFinalizationVersion(requestedVersion);
        finalization.setSegmentCount(segments.size());
        finalization.setSnapshotSha256(snapshotSha256(segments));
        finalization.setFinalizedAt(finalizedAt);
        finalization.setCreatedAt(finalizedAt);
        finalizationRepository.save(finalization);

        TranscriptEventOutbox outbox = new TranscriptEventOutbox();
        outbox.setId(deterministicId("outbox", eventKey));
        outbox.setEventType(MeetingEventType.TRANSCRIPT_READY.wireValue());
        outbox.setAggregateId(sessionId);
        outbox.setMeetingId(meetingId);
        outbox.setTenantId(tenantId);
        outbox.setOrgId(tenantId);
        outbox.setPayload(MeetingEventV1Serializer.toJson(envelope));
        outbox.setEventKey(eventKey);
        outboxRepository.save(outbox);

        association.setFinalizationVersion(requestedVersion);
        associationRepository.save(association);

        // Force all three writes inside this transaction; no caller can receive a
        // success while a deferred unique/check failure is still pending.
        finalizationRepository.flush();
        outboxRepository.flush();
        associationRepository.flush();
        return toDto(finalization, eventKey);
    }

    private void validateFinalSegments(List<TranscriptSegment> segments) {
        if (segments.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A canonical transcript needs at least one segment before finalization.");
        }
        for (TranscriptSegment segment : segments) {
            if (segment.getStatus() == TranscriptSegmentStatus.DRAFT) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "All canonical transcript segments must be finalized or redacted.");
            }
            if (segment.getStatus() == TranscriptSegmentStatus.FINALIZED
                    && !hasText(segment.getTextFinal())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Every finalized segment must contain final text.");
            }
            if (segment.getStatus() == TranscriptSegmentStatus.REDACTED
                    && (hasText(segment.getTextDraft()) || hasText(segment.getTextFinal()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Redacted segments must not retain transcript text.");
            }
        }
    }

    private String snapshotSha256(List<TranscriptSegment> segments) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (TranscriptSegment segment : segments) {
                update(digest, segment.getId().toString());
                update(digest, segment.getStatus().name());
                update(digest, segment.getTextFinal());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable.", e);
        }
    }

    private void update(MessageDigest digest, String value) {
        byte[] bytes = value == null ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private UUID deterministicId(String kind, String eventKey) {
        return UUID.nameUUIDFromBytes(
                (PRODUCER + "|" + kind + "|" + eventKey).getBytes(StandardCharsets.UTF_8));
    }

    private TranscriptFinalizationDto toDto(TranscriptFinalization row) {
        MeetingEventPayload.TranscriptReady payload = new MeetingEventPayload.TranscriptReady(
                row.getSessionId(), row.getFinalizationVersion(), row.getSegmentCount());
        return toDto(row, com.example.common.meeting.events.MeetingEventKeys.forPayload(payload));
    }

    private TranscriptFinalizationDto toDto(TranscriptFinalization row, String eventKey) {
        return new TranscriptFinalizationDto(
                row.getId(), row.getMeetingId(), row.getSessionId(),
                row.getFinalizationVersion(), row.getSegmentCount(),
                row.getFinalizedAt(), eventKey);
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
