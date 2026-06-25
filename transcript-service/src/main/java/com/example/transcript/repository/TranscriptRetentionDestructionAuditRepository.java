package com.example.transcript.repository;

import com.example.transcript.model.TranscriptRetentionDestructionAudit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TranscriptRetentionDestructionAuditRepository
        extends JpaRepository<TranscriptRetentionDestructionAudit, UUID> {

    List<TranscriptRetentionDestructionAudit> findByLayerIdOrderByExecutedAtDesc(String layerId);
}
