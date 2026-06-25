package com.example.transcript.repository;

import com.example.transcript.model.TranscriptAccessAudit;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * KVKK m.12 access-log repository. Segment access writes use the standard
 * {@code save(...)} insert path; Faz 24 retention cleanup uses id-only
 * selectors and bulk deletes so audit evidence never includes search/accessor
 * content.
 */
public interface TranscriptAccessAuditRepository extends JpaRepository<TranscriptAccessAudit, UUID> {

    @Query("""
            select a.id
            from TranscriptAccessAudit a
            where a.accessedAt < :cutoff
            order by a.accessedAt asc, a.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranscriptAccessAudit a where a.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
