package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisJobCapabilityUse;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingAnalysisJobCapabilityUseRepository
        extends JpaRepository<MeetingAnalysisJobCapabilityUse, UUID> {
}
