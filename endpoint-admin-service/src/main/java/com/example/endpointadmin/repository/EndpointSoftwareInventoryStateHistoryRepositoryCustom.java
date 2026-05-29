package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;

/**
 * BE-024 — custom write path for the append-only software-state history.
 *
 * <p>Exists so the ingest-time append can use a native
 * {@code INSERT ... ON CONFLICT (source_command_result_id)
 * WHERE source_command_result_id IS NOT NULL DO NOTHING} instead of a broad
 * {@code catch (DataIntegrityViolationException)}. Codex 019e75fe CRITICAL:
 * swallowing every {@code DataIntegrityViolationException} (a) hides a
 * non-duplicate V18 constraint / FK / CHECK breach (mis-classified as a
 * "duplicate") and (b) on PostgreSQL leaves the surrounding transaction
 * marked rollback-only, so the later audit/commit stage fails uncontrolled —
 * breaking the snapshot+result+history atomicity claim. The partial-unique
 * inference makes ONLY the duplicate {@code source_command_result_id} a
 * no-op; every other violation propagates and rolls the whole transaction
 * back together with the snapshot.
 */
public interface EndpointSoftwareInventoryStateHistoryRepositoryCustom {

    /**
     * Insert one software-state capture, treating a duplicate non-null
     * {@code source_command_result_id} as a no-op (the partial-UNIQUE index
     * {@code uq_endpoint_software_inventory_state_history_source_cmd_result}
     * is the conflict target). Every other constraint / FK / CHECK breach
     * propagates as a {@code DataIntegrityViolationException} and rolls back
     * the surrounding transaction.
     *
     * <p>The entity's {@code id} (UUID) and {@code createdAt} are normally
     * assigned by Hibernate ({@code @GeneratedValue} / {@code @PrePersist}).
     * This native path bypasses both, so the caller MUST set them before
     * calling (the service does). {@code appsDigest} is serialized to a JSON
     * string by the implementation and bound with a {@code ::jsonb} cast.
     *
     * @return {@code true} when a row was inserted, {@code false} when the
     *         duplicate {@code source_command_result_id} made it a no-op.
     */
    boolean insertIfNewSourceCommandResult(
            EndpointSoftwareInventoryStateHistory history);
}
