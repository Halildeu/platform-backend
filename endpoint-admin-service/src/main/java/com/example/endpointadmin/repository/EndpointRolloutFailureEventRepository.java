package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the append-only rollout failed-device event ledger (Faz 22.5
 * #527 slice-1a). History reads are org-scoped (the caller resolves the parent
 * failure within org scope first to avoid a cross-org leak via {@code failure_id}
 * — Codex 019eaaf0). Deterministic order by {@code (created_at, id)}.
 */
public interface EndpointRolloutFailureEventRepository
        extends JpaRepository<EndpointRolloutFailureEvent, UUID> {

    List<EndpointRolloutFailureEvent> findByFailureIdAndOrgIdOrderByCreatedAtAscIdAsc(
            UUID failureId, UUID orgId);
}
