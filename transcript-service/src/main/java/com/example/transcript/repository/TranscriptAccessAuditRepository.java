package com.example.transcript.repository;

import com.example.transcript.model.TranscriptAccessAudit;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * KVKK m.12 access-log repository. This service is a WRITE-only producer here
 * (the retention worker #1250 owns the read/delete lifecycle). The standard
 * {@code save(...)} insert path is all the segment service needs.
 */
public interface TranscriptAccessAuditRepository extends JpaRepository<TranscriptAccessAudit, UUID> {
}
