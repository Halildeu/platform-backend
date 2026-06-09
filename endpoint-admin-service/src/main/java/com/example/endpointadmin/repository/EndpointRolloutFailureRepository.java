package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutFailure;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the rollout failed-device queue aggregate (Faz 22.5 #527
 * slice-1a). Every lookup is org-scoped (org_id == tenant_id) — never by bare
 * id — to forbid cross-org reads (contract / Codex 019eaaf0).
 */
public interface EndpointRolloutFailureRepository extends JpaRepository<EndpointRolloutFailure, UUID> {

    Optional<EndpointRolloutFailure> findByIdAndOrgId(UUID id, UUID orgId);

    /**
     * The ONE active aggregate for a wave-device, if any. Mirrors the partial
     * unique predicate {@code current_state IN ('new','retrying','quarantined',
     * 'escalated')}; used for the manual-create 409 pre-check (the DB unique
     * index is the race-safe authority).
     */
    @Query("""
            SELECT f FROM EndpointRolloutFailure f
            WHERE f.orgId = :orgId AND f.rolloutId = :rolloutId
              AND f.waveId = :waveId AND f.deviceId = :deviceId
              AND f.currentState IN (
                com.example.endpointadmin.model.RolloutFailureState.NEW,
                com.example.endpointadmin.model.RolloutFailureState.RETRYING,
                com.example.endpointadmin.model.RolloutFailureState.QUARANTINED,
                com.example.endpointadmin.model.RolloutFailureState.ESCALATED)
            """)
    Optional<EndpointRolloutFailure> findActive(@Param("orgId") UUID orgId,
                                                @Param("rolloutId") String rolloutId,
                                                @Param("waveId") String waveId,
                                                @Param("deviceId") UUID deviceId);

    List<EndpointRolloutFailure> findByOrgIdAndRolloutIdAndWaveIdOrderByFirstDetectedAtDescIdDesc(
            UUID orgId, String rolloutId, String waveId);
}
