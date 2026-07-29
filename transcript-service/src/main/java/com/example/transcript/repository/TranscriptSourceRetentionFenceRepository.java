package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSourceRetentionFence;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TranscriptSourceRetentionFenceRepository
        extends JpaRepository<TranscriptSourceRetentionFence, UUID> {

    boolean existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceTransportEpochAndSourceWindowSeq(
            UUID tenantId,
            UUID meetingId,
            String sourceSessionHash,
            long sourceTransportEpoch,
            long sourceWindowSeq);
}
