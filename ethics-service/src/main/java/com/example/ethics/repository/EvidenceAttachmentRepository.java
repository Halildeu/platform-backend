package com.example.ethics.repository;

import com.example.ethics.model.EvidenceAttachment;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Pageable;

public interface EvidenceAttachmentRepository extends JpaRepository<EvidenceAttachment, UUID> {
    Optional<EvidenceAttachment> findByCaseIdAndIdempotencyKey(UUID caseId, String idempotencyKey);
    Optional<EvidenceAttachment> findByUploadCapabilityHashAndChannel(String uploadCapabilityHash, String channel);
    List<EvidenceAttachment> findAllByCaseIdOrderByCreatedAtAsc(UUID caseId);
    Optional<EvidenceAttachment> findByIdAndCaseId(UUID id, UUID caseId);
    long countByStateIn(Collection<String> states);

    @Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select e from EvidenceAttachment e where e.id = :id")
    Optional<EvidenceAttachment> findLockedById(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            UPDATE {h-schema}ethics_evidence_attachments
            SET state = 'ORIGINAL_SEALED',
                claim_token = NULL,
                locked_until = NULL,
                next_attempt_at = :now,
                failure_code = 'LEASE_EXPIRED',
                updated_at = :now
            WHERE state IN ('SCANNING','SANITIZING')
              AND locked_until < :now
            """, nativeQuery = true)
    int recoverExpiredLeases(@Param("now") Instant now);

    @Query("""
            select e.id
            from EvidenceAttachment e
            where e.state in ('INTEGRITY_VERIFIED','ORIGINAL_SEALED','SCAN_PENDING')
              and (e.nextAttemptAt is null or e.nextAttemptAt <= :now)
            order by e.createdAt, e.id
            """)
    List<UUID> findDueIds(@Param("now") Instant now, Pageable pageable);

    @Query("""
            select e.id
            from EvidenceAttachment e
            where e.state = 'UPLOADING'
              and e.uploadConsumedAt is null
              and e.uploadExpiresAt <= :now
            order by e.uploadExpiresAt, e.id
            """)
    List<UUID> findExpiredUnboundIds(@Param("now") Instant now, Pageable pageable);
}
