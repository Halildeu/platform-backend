package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSegment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Transcript segment repository.
 *
 * <p>Every read uses the canonical effective-org predicate
 * {@code (s.orgId = :orgId OR (s.orgId IS NULL AND s.tenantId = :orgId))} so it
 * accepts both canonical rows (org_id = tenant_id) and legacy null rows
 * (org_id IS NULL AND tenant_id = :orgId) without an existence leak across
 * tenants. {@code orgId} is the caller's canonical tenant scope.
 */
public interface TranscriptSegmentRepository extends JpaRepository<TranscriptSegment, UUID> {

    /**
     * Effective-org single-segment ownership gate. Empty Optional → admin
     * action 404 (no existence leak across tenants).
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.id = :id
            """)
    Optional<TranscriptSegment> findVisibleToOrgAndId(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /**
     * Effective-org listing of a meeting's segments, ordered by start time.
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.meetingId = :meetingId
            order by s.startTime asc, s.sourceChunkSeq asc, s.id asc
            """)
    Page<TranscriptSegment> findVisibleToOrgByMeeting(
            @Param("orgId") UUID orgId, @Param("meetingId") UUID meetingId, Pageable pageable);

    /**
     * Effective-org listing of a session's segments, ordered by start time.
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.sessionId = :sessionId
            order by s.startTime asc, s.sourceChunkSeq asc, s.id asc
            """)
    Page<TranscriptSegment> findVisibleToOrgBySession(
            @Param("orgId") UUID orgId, @Param("sessionId") UUID sessionId, Pageable pageable);

    /**
     * Effective-org full-text-ish search over the segment text (draft + final),
     * case-insensitive substring. {@code term} is the already-lowercased needle;
     * the predicate uses {@code lower(...) like %term%}. Optionally narrowed to a
     * single meeting when {@code meetingId} is non-null.
     *
     * <p>NOTE: the search TERM is never persisted — only the fact that a SEARCH
     * happened + the result count is written to the KVKK m.12 access audit (see
     * {@code TranscriptSegmentService}).
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and (:meetingId is null or s.meetingId = :meetingId)
              and (
                    lower(coalesce(s.textFinal, '')) like concat('%', :term, '%')
                 or lower(coalesce(s.textDraft, '')) like concat('%', :term, '%')
              )
            order by s.startTime asc, s.sourceChunkSeq asc, s.id asc
            """)
    Page<TranscriptSegment> searchVisibleToOrg(
            @Param("orgId") UUID orgId,
            @Param("meetingId") UUID meetingId,
            @Param("term") String term,
            Pageable pageable);

    /**
     * Effective-org export selector for a meeting (list form; the export path
     * streams these into CSV/JSON). Capped by the caller before materializing.
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.meetingId = :meetingId
            order by s.startTime asc, s.sourceChunkSeq asc, s.id asc
            """)
    List<TranscriptSegment> findAllVisibleToOrgByMeeting(
            @Param("orgId") UUID orgId, @Param("meetingId") UUID meetingId, Pageable pageable);

    @Query("""
            select s
            from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.sourceSystem = 'DIRECT_STT'
              and s.sourceSessionId = :sourceSessionId
              and s.sourceChunkSeq = :sourceChunkSeq
            """)
    Optional<TranscriptSegment> findDirectSttSourceChunk(
            @Param("tenantId") UUID tenantId,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("sourceChunkSeq") Long sourceChunkSeq);

    @Query("""
            select s.id
            from TranscriptSegment s
            where s.createdAt < :cutoff
            order by s.createdAt asc, s.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from TranscriptSegment s where s.id in :ids")
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);
}
