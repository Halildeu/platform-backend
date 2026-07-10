package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisCitation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/** Repository for {@link MeetingAnalysisCitation} — #244 BE-1. */
public interface MeetingAnalysisCitationRepository extends JpaRepository<MeetingAnalysisCitation, UUID> {
}
