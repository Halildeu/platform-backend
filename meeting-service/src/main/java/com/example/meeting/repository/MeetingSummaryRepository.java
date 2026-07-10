package com.example.meeting.repository;

import com.example.meeting.model.MeetingSummary;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** Repository for {@link MeetingSummary} — #244 BE-1. */
public interface MeetingSummaryRepository extends JpaRepository<MeetingSummary, UUID> {

    Optional<MeetingSummary> findByAnalysisRunId(UUID analysisRunId);
}
