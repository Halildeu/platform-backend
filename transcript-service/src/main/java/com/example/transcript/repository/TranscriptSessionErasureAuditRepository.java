package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSessionErasureAudit;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptSessionErasureAuditRepository
        extends JpaRepository<TranscriptSessionErasureAudit, UUID> {
    List<TranscriptSessionErasureAudit> findByTombstoneIdOrderByExecutedAtAsc(UUID tombstoneId);
}
