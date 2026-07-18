package com.example.meeting.repository;

import com.example.meeting.model.MeetingSessionErasureAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MeetingSessionErasureAuditRepository
        extends JpaRepository<MeetingSessionErasureAudit, UUID> {
    List<MeetingSessionErasureAudit> findBySessionIdOrderByExecutedAtAsc(UUID sessionId);
}
