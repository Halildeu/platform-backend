package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.RemoteViewPolicyPublication;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RemoteViewPolicyPublicationRepository
        extends JpaRepository<RemoteViewPolicyPublication, UUID> {

    Optional<RemoteViewPolicyPublication> findByTenantIdAndApprovalId(UUID tenantId, UUID approvalId);

    Optional<RemoteViewPolicyPublication> findByTenantIdAndId(UUID tenantId, UUID id);

    Optional<RemoteViewPolicyPublication> findByTenantIdAndPolicyDigest(UUID tenantId, String policyDigest);

    @Query("""
            SELECT p FROM RemoteViewPolicyPublication p
             WHERE p.tenantId = :tenantId
               AND p.validFrom <= :now
             ORDER BY p.validFrom DESC, p.publishedAt DESC, p.id DESC
            """)
    java.util.List<RemoteViewPolicyPublication> findEffectiveCandidates(
            @Param("tenantId") UUID tenantId, @Param("now") Instant now,
            org.springframework.data.domain.Pageable pageable);

    @Query("""
            SELECT p FROM RemoteViewPolicyPublication p
             WHERE p.tenantId = :tenantId
             ORDER BY p.publishedAt DESC, p.id DESC
            """)
    java.util.List<RemoteViewPolicyPublication> findLatestCandidates(
            @Param("tenantId") UUID tenantId, org.springframework.data.domain.Pageable pageable);

    default Optional<RemoteViewPolicyPublication> findEffective(UUID tenantId, Instant now) {
        return findEffectiveCandidates(tenantId, now, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }

    default Optional<RemoteViewPolicyPublication> findLatest(UUID tenantId) {
        return findLatestCandidates(tenantId, org.springframework.data.domain.PageRequest.of(0, 1))
                .stream().findFirst();
    }
}
