package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisRejectedClaim;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Repository for {@link MeetingAnalysisRejectedClaim} — #244 BE-1. */
public interface MeetingAnalysisRejectedClaimRepository extends JpaRepository<MeetingAnalysisRejectedClaim, UUID> {
}
