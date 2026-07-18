package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisRunDestructionTombstone;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingAnalysisRunDestructionTombstoneRepository
        extends JpaRepository<MeetingAnalysisRunDestructionTombstone, UUID> {

    Optional<MeetingAnalysisRunDestructionTombstone>
            findByAnalysisRunIdAndTenantIdAndMeetingId(
                    UUID analysisRunId, UUID tenantId, UUID meetingId);
}
