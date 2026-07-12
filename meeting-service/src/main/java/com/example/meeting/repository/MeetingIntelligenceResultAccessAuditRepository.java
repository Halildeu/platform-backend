package com.example.meeting.repository;

import com.example.meeting.model.MeetingIntelligenceResultAccessAudit;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Metadata-only successful result-access audit repository. */
public interface MeetingIntelligenceResultAccessAuditRepository
        extends JpaRepository<MeetingIntelligenceResultAccessAudit, UUID> {

    List<MeetingIntelligenceResultAccessAudit> findByTenantIdOrderByAccessedAtDesc(UUID tenantId);

    @Query("""
            select a.id
            from MeetingIntelligenceResultAccessAudit a
            where a.accessedAt < :cutoff
            order by a.accessedAt asc, a.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from MeetingIntelligenceResultAccessAudit a where a.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
