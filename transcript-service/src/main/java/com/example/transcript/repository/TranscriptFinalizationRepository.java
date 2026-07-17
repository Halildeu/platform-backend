package com.example.transcript.repository;

import com.example.transcript.model.TranscriptFinalization;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptFinalizationRepository extends JpaRepository<TranscriptFinalization, UUID> {
    Optional<TranscriptFinalization> findByTenantIdAndMeetingIdAndSessionIdAndFinalizationVersion(
            UUID tenantId, UUID meetingId, UUID sessionId, long finalizationVersion);
}
