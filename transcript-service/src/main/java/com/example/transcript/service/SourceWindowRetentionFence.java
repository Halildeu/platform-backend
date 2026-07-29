package com.example.transcript.service;

import com.example.transcript.directstt.DirectSttTranscriptResultEvent;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSourceRetentionFence;
import com.example.transcript.repository.TranscriptSourceRetentionFenceRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Permanent metadata-only rejection ledger for retained-out Direct-STT windows. */
@Component
public class SourceWindowRetentionFence {

    private final TranscriptSourceRetentionFenceRepository fences;
    private final SessionErasureFence advisoryLocks;

    public SourceWindowRetentionFence(
            TranscriptSourceRetentionFenceRepository fences,
            SessionErasureFence advisoryLocks) {
        this.fences = fences;
        this.advisoryLocks = advisoryLocks;
    }

    public void lockAndRejectRetained(
            UUID tenantId,
            UUID meetingId,
            String sourceSessionId,
            long sourceTransportEpoch,
            long sourceWindowSeq) {
        String sourceHash = SessionErasureFence.sourceHash(sourceSessionId);
        advisoryLocks.lock(
                windowKey(tenantId, meetingId, sourceHash, 0L, sourceWindowSeq),
                windowKey(
                        tenantId, meetingId, sourceHash,
                        sourceTransportEpoch, sourceWindowSeq));
        boolean retained = sourceHash != null
                && fences
                .existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceTransportEpochAndSourceWindowSeq(
                        tenantId, meetingId, sourceHash,
                        sourceTransportEpoch, sourceWindowSeq);
        // V12 backfills pre-epoch fences to epoch 0. Treat that legacy marker as
        // covering later epoch-aware replays too: after source content has been
        // destroyed, privacy wins over accepting an ambiguous counter restart.
        boolean retainedByLegacyFence = sourceHash != null
                && sourceTransportEpoch != 0
                && fences
                .existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceTransportEpochAndSourceWindowSeq(
                        tenantId, meetingId, sourceHash, 0L, sourceWindowSeq);
        if (retained || retainedByLegacyFence) {
            throw new SourceWindowRetainedException();
        }
    }

    public void lockWindows(Collection<TranscriptSegment> segments) {
        advisoryLocks.lock(segments.stream()
                .filter(segment -> DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(
                        segment.getSourceSystem()))
                .filter(segment -> segment.getSourceSessionId() != null
                        && !segment.getSourceSessionId().isBlank()
                        && segment.getSourceTransportEpoch() != null
                        && segment.getSourceWindowSeq() != null)
                .map(segment -> windowKey(
                        segment.getTenantId(), segment.getMeetingId(),
                        SessionErasureFence.sourceHash(segment.getSourceSessionId()),
                        segment.getSourceTransportEpoch(),
                        segment.getSourceWindowSeq()))
                .toArray(String[]::new));
    }

    public int recordDestroyed(Collection<TranscriptSegment> segments, Instant retainedAt) {
        Map<WindowKey, TranscriptSegment> uniqueWindows = new LinkedHashMap<>();
        for (TranscriptSegment segment : segments) {
            if (DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(segment.getSourceSystem())
                    && segment.getSourceSessionId() != null
                    && !segment.getSourceSessionId().isBlank()
                    && segment.getSourceTransportEpoch() != null
                    && segment.getSourceWindowSeq() != null) {
                String sourceHash = SessionErasureFence.sourceHash(segment.getSourceSessionId());
                uniqueWindows.putIfAbsent(
                        new WindowKey(
                                segment.getTenantId(), segment.getMeetingId(), sourceHash,
                                segment.getSourceTransportEpoch(),
                                segment.getSourceWindowSeq()),
                        segment);
            }
        }

        int recorded = 0;
        for (Map.Entry<WindowKey, TranscriptSegment> entry : uniqueWindows.entrySet()) {
            WindowKey key = entry.getKey();
            TranscriptSegment segment = entry.getValue();
            if (fences
                    .existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceTransportEpochAndSourceWindowSeq(
                            key.tenantId(), key.meetingId(), key.sourceSessionHash(),
                            key.sourceTransportEpoch(), key.sourceWindowSeq())) {
                continue;
            }
            TranscriptSourceRetentionFence fence = new TranscriptSourceRetentionFence();
            fence.setId(UUID.randomUUID());
            fence.setTenantId(key.tenantId());
            fence.setOrgId(key.tenantId());
            fence.setMeetingId(key.meetingId());
            fence.setSessionId(segment.getSessionId());
            fence.setSourceSessionHash(key.sourceSessionHash());
            fence.setSourceTransportEpoch(key.sourceTransportEpoch());
            fence.setSourceWindowSeq(key.sourceWindowSeq());
            fence.setRetainedAt(retainedAt);
            fences.save(fence);
            recorded++;
        }
        return recorded;
    }

    private record WindowKey(
            UUID tenantId,
            UUID meetingId,
            String sourceSessionHash,
            long sourceTransportEpoch,
            long sourceWindowSeq) {}

    private static String windowKey(
            UUID tenantId,
            UUID meetingId,
            String sourceHash,
            long sourceTransportEpoch,
            long sourceWindowSeq) {
        return sourceHash == null ? null
                : "retention-window|" + tenantId + "|" + meetingId + "|"
                + sourceHash + "|" + sourceTransportEpoch + "|" + sourceWindowSeq;
    }

    public static class SourceWindowRetainedException extends IllegalStateException {
        public SourceWindowRetainedException() {
            super("source window was permanently removed by retention");
        }
    }
}
