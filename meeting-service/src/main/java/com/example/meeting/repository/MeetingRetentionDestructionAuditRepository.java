package com.example.meeting.repository;

import com.example.meeting.model.MeetingRetentionDestructionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MeetingRetentionDestructionAuditRepository
        extends JpaRepository<MeetingRetentionDestructionAudit, UUID> {

    List<MeetingRetentionDestructionAudit> findByLayerIdOrderByExecutedAtDesc(String layerId);
}
