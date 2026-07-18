package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSessionErasureTombstone;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TranscriptSessionErasureTombstoneRepository
        extends JpaRepository<TranscriptSessionErasureTombstone, UUID> {

    /** PostgreSQL transaction-scoped lock for absent-row canonical/source fences. */
    @Query(value = """
            select 1
            from pg_advisory_xact_lock(hashtextextended(cast(:fenceKey as text), 0))
            """, nativeQuery = true)
    int acquireFence(@Param("fenceKey") String fenceKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select t from TranscriptSessionErasureTombstone t
            where t.tenantId = :tenantId and t.meetingId = :meetingId and t.sessionId = :sessionId
            """)
    Optional<TranscriptSessionErasureTombstone> findSessionForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    Optional<TranscriptSessionErasureTombstone> findByTenantIdAndMeetingIdAndSessionId(
            UUID tenantId, UUID meetingId, UUID sessionId);

    Optional<TranscriptSessionErasureTombstone> findByTenantIdAndMeetingIdAndSourceSessionHash(
            UUID tenantId, UUID meetingId, String sourceSessionHash);

    boolean existsByTenantIdAndMeetingIdAndSessionId(UUID tenantId, UUID meetingId, UUID sessionId);

    boolean existsByTenantIdAndMeetingIdAndSourceSessionHash(
            UUID tenantId, UUID meetingId, String sourceSessionHash);
}
