package com.example.transcript.attribution;

import com.example.common.meeting.events.SpeakerAttribution;
import com.example.transcript.attribution.SessionAttributionEvent.WindowAssignment;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.service.SessionErasureFence;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a post-session diarization event to the session's stored windows
 * (#3746 BE-D4b, design D5).
 *
 * <p>This is a DISTINCT service flow, not an ingest replay: committed windows are never
 * re-published and the ingest idempotency contract is untouched. For each window
 * assignment the service derives the {@link SpeakerAttribution} v2 turn from the stored
 * window text it owns (the producer never sees text): one turn covering the whole
 * window text, acoustic span = the window's own duration, {@code UU} for contested or
 * silent windows. The scalar {@code speaker_id} fills only for a known single speaker —
 * exactly the shape the admin API and the web transcript view already consume.
 *
 * <p>Fail-closed edges: the erasure fence rejects sessions under erasure; REDACTED
 * windows are never touched; a missing window (dropped forward) is skipped and counted;
 * a conflicting existing attribution is left alone (first write wins — labels must
 * never flap after a redelivery or a duplicate batch).
 */
@Service
public class SessionAttributionApplyService {

    private static final Logger log =
            LoggerFactory.getLogger(SessionAttributionApplyService.class);

    private final TranscriptSegmentRepository segments;
    private final SessionErasureFence erasureFence;

    public SessionAttributionApplyService(
            TranscriptSegmentRepository segments,
            SessionErasureFence erasureFence) {
        this.segments = segments;
        this.erasureFence = erasureFence;
    }

    public record ApplyOutcome(
            int applied, int alreadyApplied, int missingWindows, int skippedRedacted,
            int invalidWindows) {
    }

    @Transactional
    public ApplyOutcome apply(SessionAttributionEvent event) {
        Objects.requireNonNull(event, "event");
        // Source-scoped fence: attribution addresses windows by their SOURCE session
        // identity and needs no canonical session id; the source tombstone hash is the
        // erasure authority for that identity.
        erasureFence.lock(SessionErasureFence.sourceKey(
                event.tenantId(), event.meetingId(), event.sourceSessionId()));
        erasureFence.rejectSourceErased(
                event.tenantId(), event.meetingId(), event.sourceSessionId());

        int applied = 0;
        int alreadyApplied = 0;
        int missing = 0;
        int redacted = 0;
        int invalid = 0;
        for (final WindowAssignment assignment : event.windows()) {
            final TranscriptSegment segment = segments.findDirectSttSourceTransportWindow(
                    event.tenantId(), event.meetingId(), event.sourceSessionId(),
                    event.transportEpoch(), assignment.windowSeq()).orElse(null);
            if (segment == null) {
                missing++;
                continue;
            }
            if (segment.getStatus() == TranscriptSegmentStatus.REDACTED) {
                redacted++;
                continue;
            }
            if (segment.getSpeakerAttribution() != null) {
                alreadyApplied++;
                continue;
            }
            final SpeakerAttribution attribution =
                    buildWindowAttribution(event, assignment, segment);
            if (attribution == null) {
                invalid++;
                continue;
            }
            segment.setSpeakerAttribution(attribution);
            segment.setSpeakerId(assignment.unknown()
                    ? null
                    : attribution.speakerId(assignment.speaker()));
            segments.save(segment);
            applied++;
        }
        final ApplyOutcome outcome =
                new ApplyOutcome(applied, alreadyApplied, missing, redacted, invalid);
        log.info(
                "session attribution applied meetingId={} sourceSessionId={} epoch={} "
                        + "applied={} already={} missing={} redacted={} invalid={}",
                event.meetingId(), event.sourceSessionId(), event.transportEpoch(),
                outcome.applied(), outcome.alreadyApplied(), outcome.missingWindows(),
                outcome.skippedRedacted(), outcome.invalidWindows());
        return outcome;
    }

    /**
     * One whole-window turn on that window's own text and duration — the only shape the
     * producer's knowledge supports (design D3/D5). Returns null when the stored window
     * cannot carry a contract-valid turn (blank text, non-positive duration).
     */
    private static SpeakerAttribution buildWindowAttribution(
            SessionAttributionEvent event,
            WindowAssignment assignment,
            TranscriptSegment segment) {
        final String text = segment.getTextFinal() != null
                ? segment.getTextFinal() : segment.getTextDraft();
        if (text == null || text.isBlank()) {
            return null;
        }
        final double start = segment.getStartTime() == null ? 0d : segment.getStartTime();
        final double end = segment.getEndTime() == null ? 0d : segment.getEndTime();
        final long durationMs = Math.round((end - start) * 1000d);
        if (durationMs <= 0) {
            return null;
        }
        try {
            return new SpeakerAttribution(
                    SpeakerAttribution.scope(
                            event.sourceTenantId(),
                            event.meetingId().toString(),
                            event.sourceSessionId(),
                            event.transportEpoch()),
                    List.of(new SpeakerAttribution.Turn(
                            assignment.speaker(), 0, text.length(), 0L, durationMs)));
        } catch (final IllegalArgumentException ex) {
            return null;
        }
    }
}
