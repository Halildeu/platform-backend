package com.example.meeting.repository;

import com.example.meeting.model.Meeting;
import com.example.meeting.model.MeetingStatus;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;
import java.time.Instant;

/**
 * Repository for {@link Meeting} — Faz 24 (#410).
 *
 * <p>Every read is org-scoped at the SQL layer with the canonical
 * effective-org predicate (endpoint-admin
 * {@code findVisibleToOrgAndId} pattern):
 * <pre>
 *   (m.orgId = :orgId OR (m.orgId IS NULL AND m.tenantId = :orgId))
 * </pre>
 * which accepts canonical rows ({@code org_id = tenant_id}) and legacy
 * rows ({@code org_id IS NULL AND tenant_id = :orgId}) via a
 * parenthesized OR. {@code :orgId} is the caller's canonical scope
 * (== legacy {@code tenantId}). An empty {@link Optional} drives a 404
 * with no existence leak.
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    @Query("""
            select m
            from Meeting m
            where (m.orgId = :orgId or (m.orgId is null and m.tenantId = :orgId))
              and m.id = :id
            """)
    Optional<Meeting> findVisibleToOrgAndId(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select m
            from Meeting m
            where (m.orgId = :orgId or (m.orgId is null and m.tenantId = :orgId))
              and m.id = :id
            """)
    Optional<Meeting> findVisibleToOrgAndIdForUpdate(
            @Param("orgId") UUID orgId, @Param("id") UUID id);

    @Query(value = """
            select m
            from Meeting m
            where (m.orgId = :orgId or (m.orgId is null and m.tenantId = :orgId))
              and (:statusFilter = false or m.status = :status)
              and (:meetingIdFilter = false or m.id = :meetingId)
              and (:titleFilter = false or locate(lower(:title), lower(m.title)) > 0)
              and (:dateFilter = false or (m.startedAt >= :dateFrom and m.startedAt < :dateTo))
            order by m.startedAt desc, m.id desc
            """, countQuery = """
            select count(m)
            from Meeting m
            where (m.orgId = :orgId or (m.orgId is null and m.tenantId = :orgId))
              and (:statusFilter = false or m.status = :status)
              and (:meetingIdFilter = false or m.id = :meetingId)
              and (:titleFilter = false or locate(lower(:title), lower(m.title)) > 0)
              and (:dateFilter = false or (m.startedAt >= :dateFrom and m.startedAt < :dateTo))
            """)
    Page<Meeting> searchVisibleToOrg(
            @Param("orgId") UUID orgId,
            @Param("statusFilter") boolean statusFilter,
            @Param("status") MeetingStatus status,
            @Param("titleFilter") boolean titleFilter,
            @Param("title") String title,
            @Param("meetingIdFilter") boolean meetingIdFilter,
            @Param("meetingId") UUID meetingId,
            @Param("dateFilter") boolean dateFilter,
            @Param("dateFrom") Instant dateFrom,
            @Param("dateTo") Instant dateTo,
            Pageable pageable);
}
