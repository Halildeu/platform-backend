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

    public SourceWindowRetentionFence(TranscriptSourceRetentionFenceRepository fences) {
        this.fences = fences;
    }

    public void rejectRetained(
            UUID tenantId, UUID meetingId, String sourceSessionId, long sourceWindowSeq) {
        String sourceHash = SessionErasureFence.sourceHash(sourceSessionId);
        if (sourceHash != null
                && fences.existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceWindowSeq(
                        tenantId, meetingId, sourceHash, sourceWindowSeq)) {
            throw new SourceWindowRetainedException();
        }
    }

    public int recordDestroyed(Collection<TranscriptSegment> segments, Instant retainedAt) {
        Map<WindowKey, TranscriptSegment> uniqueWindows = new LinkedHashMap<>();
        for (TranscriptSegment segment : segments) {
            if (DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(segment.getSourceSystem())
                    && segment.getSourceSessionId() != null
                    && !segment.getSourceSessionId().isBlank()
                    && segment.getSourceWindowSeq() != null) {
                String sourceHash = SessionErasureFence.sourceHash(segment.getSourceSessionId());
                uniqueWindows.putIfAbsent(
                        new WindowKey(
                                segment.getTenantId(), segment.getMeetingId(), sourceHash,
                                segment.getSourceWindowSeq()),
                        segment);
            }
        }

        int recorded = 0;
        for (Map.Entry<WindowKey, TranscriptSegment> entry : uniqueWindows.entrySet()) {
            WindowKey key = entry.getKey();
            TranscriptSegment segment = entry.getValue();
            if (fences.existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceWindowSeq(
                    key.tenantId(), key.meetingId(), key.sourceSessionHash(),
                    key.sourceWindowSeq())) {
                continue;
            }
            TranscriptSourceRetentionFence fence = new TranscriptSourceRetentionFence();
            fence.setId(UUID.randomUUID());
            fence.setTenantId(key.tenantId());
            fence.setOrgId(key.tenantId());
            fence.setMeetingId(key.meetingId());
            fence.setSessionId(segment.getSessionId());
            fence.setSourceSessionHash(key.sourceSessionHash());
            fence.setSourceWindowSeq(key.sourceWindowSeq());
            fence.setRetainedAt(retainedAt);
            fences.save(fence);
            recorded++;
        }
        return recorded;
    }

    private record WindowKey(
            UUID tenantId, UUID meetingId, String sourceSessionHash, long sourceWindowSeq) {}

    public static class SourceWindowRetainedException extends IllegalStateException {
        public SourceWindowRetainedException() {
            super("source window was permanently removed by retention");
        }
    }
}
