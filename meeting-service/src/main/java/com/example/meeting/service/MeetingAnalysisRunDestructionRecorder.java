package com.example.meeting.service;

import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.model.MeetingAnalysisRunDestructionTombstone;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Persists content-free evidence only for analysis rows confirmed destroyed. */
@Component
public class MeetingAnalysisRunDestructionRecorder {

    private final MeetingAnalysisRunRepository analysisRuns;
    private final MeetingAnalysisRunDestructionTombstoneRepository tombstones;

    public MeetingAnalysisRunDestructionRecorder(
            MeetingAnalysisRunRepository analysisRuns,
            MeetingAnalysisRunDestructionTombstoneRepository tombstones) {
        this.analysisRuns = analysisRuns;
        this.tombstones = tombstones;
    }

    public int recordDestroyed(
            Collection<MeetingAnalysisRun> candidates,
            MeetingAnalysisRunDestructionReason reason,
            Instant destroyedAt) {
        int recorded = 0;
        for (MeetingAnalysisRun run : candidates) {
            UUID analysisRunId = run.getAnalysisRunId();
            if (analysisRuns.existsById(analysisRunId) || tombstones.existsById(analysisRunId)) {
                continue;
            }
            MeetingAnalysisRunDestructionTombstone row =
                    new MeetingAnalysisRunDestructionTombstone();
            row.setAnalysisRunId(analysisRunId);
            row.setTenantId(run.getTenantId());
            row.setOrgId(run.getOrgId() == null ? run.getTenantId() : run.getOrgId());
            row.setMeetingId(run.getMeetingId());
            row.setSessionId(canonicalUuidOrNull(run.getTranscriptSessionId()));
            row.setReason(reason);
            row.setDestroyedAt(destroyedAt);
            tombstones.save(row);
            recorded++;
        }
        tombstones.flush();
        return recorded;
    }

    private static UUID canonicalUuidOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ignored) {
            // Legacy external aliases are intentionally not retained.
            return null;
        }
    }
}
