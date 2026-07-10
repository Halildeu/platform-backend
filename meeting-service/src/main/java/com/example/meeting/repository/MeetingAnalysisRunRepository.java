package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Repository for {@link MeetingAnalysisRun} — #244 BE-1. */
public interface MeetingAnalysisRunRepository extends JpaRepository<MeetingAnalysisRun, UUID> {

    Optional<MeetingAnalysisRun> findByAnalysisRunId(String analysisRunId);

    Optional<MeetingAnalysisRun> findByMeetingIdAndTranscriptRevisionAndAnalyzerContractVersion(
            UUID meetingId, String transcriptRevision, String analyzerContractVersion);

    Optional<MeetingAnalysisRun> findByMeetingIdAndStatus(UUID meetingId, MeetingAnalysisRunStatus status);
}
