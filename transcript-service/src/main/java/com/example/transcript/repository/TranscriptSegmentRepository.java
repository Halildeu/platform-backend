package com.example.transcript.repository;

import com.example.transcript.model.TranscriptSegment;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * Content-free scope lookup used before acquiring mutation locks. The
     * canonical association is always locked before the segment row itself.
     */
    @Query("""
            select new com.example.transcript.repository.TranscriptSegmentMutationScope(
                s.meetingId, s.sessionId)
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.id = :id
            """)
    Optional<TranscriptSegmentMutationScope> findVisibleMutationScope(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /** Effective-org mutation selector, acquired after the association lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.id = :id
            """)
    Optional<TranscriptSegment> findVisibleToOrgAndIdForUpdate(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    /**
     * Effective-org listing of a meeting's segments, ordered by start time.
     */
    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :orgId or (s.orgId is null and s.tenantId = :orgId))
              and s.meetingId = :meetingId
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
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
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
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
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
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
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
            """)
    List<TranscriptSegment> findAllVisibleToOrgByMeeting(
            @Param("orgId") UUID orgId, @Param("meetingId") UUID meetingId, Pageable pageable);

    @Query("""
            select s
            from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sourceSystem = 'DIRECT_STT'
              and s.sourceSessionId = :sourceSessionId
              and s.sourceWindowSeq = :sourceWindowSeq
            """)
    Optional<TranscriptSegment> findDirectSttSourceWindow(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("sourceWindowSeq") Long sourceWindowSeq);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            update TranscriptSegment s
            set s.sessionId = :sessionId
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sourceSystem = 'DIRECT_STT'
              and s.sourceSessionId = :sourceSessionId
              and s.sessionId is null
            """)
    int backfillDirectSttSessionId(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("sessionId") UUID sessionId);

    @Query("""
            select count(s)
            from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sourceSystem = 'DIRECT_STT'
              and s.sourceSessionId = :sourceSessionId
              and s.sessionId is not null
              and s.sessionId <> :sessionId
            """)
    long countDirectSttSessionConflicts(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSessionId") String sourceSessionId,
            @Param("sessionId") UUID sessionId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select s
            from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sessionId = :sessionId
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
            """)
    List<TranscriptSegment> findCanonicalSessionForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Query("""
            select s
            from TranscriptSegment s
            where (s.orgId = :tenantId or (s.orgId is null and s.tenantId = :tenantId))
              and s.meetingId = :meetingId
              and s.sessionId = :sessionId
            order by s.startTime asc, coalesce(s.sourceWindowSeq, s.sourceChunkSeq) asc, s.id asc
            """)
    List<TranscriptSegment> findCanonicalFinalizedSession(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Query("""
            select s.id
            from TranscriptSegment s
            where s.createdAt < :cutoff
              and not exists (
                    select f.id
                    from TranscriptFinalization f
                    where f.tenantId = s.tenantId
                      and f.meetingId = s.meetingId
                      and f.sessionId = s.sessionId
                      and f.legalHold = true
              )
            order by s.createdAt asc, s.id asc
            """)
    List<UUID> findExpiredIds(@Param("cutoff") Instant cutoff, Pageable pageable);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TranscriptSegment s
            where s.id in :ids
              and not exists (
                    select f.id from TranscriptFinalization f
                    where f.tenantId = s.tenantId
                      and f.meetingId = s.meetingId
                      and f.sessionId = s.sessionId
                      and f.legalHold = true
              )
            """)
    int deleteByIdIn(@Param("ids") Collection<UUID> ids);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sessionId = :sessionId
            """)
    int deleteCanonicalErasureScope(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sessionId") UUID sessionId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("""
            delete from TranscriptSegment s
            where s.tenantId = :tenantId
              and s.meetingId = :meetingId
              and s.sessionId is null
              and s.sourceSystem = 'DIRECT_STT'
              and s.sourceSessionId = :sourceSessionId
            """)
    int deleteLegacySourceErasureScope(
            @Param("tenantId") UUID tenantId,
            @Param("meetingId") UUID meetingId,
            @Param("sourceSessionId") String sourceSessionId);
}
