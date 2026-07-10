package com.example.meeting.repository;

import com.example.meeting.model.MeetingAnalysisOutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Repository for {@link MeetingAnalysisOutboxEvent} — #244 BE-1. A polling
 * consumer (backend#412's {@code summary.ready}/{@code action.assigned}
 * publish step) is a separate follow-up; this slice only guarantees the rows
 * are written durably in the same transaction as the data they describe.
 */
public interface MeetingAnalysisOutboxEventRepository extends JpaRepository<MeetingAnalysisOutboxEvent, UUID> {
}
