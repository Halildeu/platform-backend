package com.example.permission.dto.v1;

import java.util.List;

/**
 * Codex 019dda05 iter-25: typed read endpoint payload for the role
 * granule snapshot. Returned by {@code GET /api/v1/roles/{roleId}/granules}
 * (the read counterpart of {@code PUT /api/v1/roles/{roleId}/granules}).
 *
 * <p>This is the source-of-truth response for the role drawer's granule
 * state. Unlike {@link com.example.permission.dto.access.AccessRoleDto}
 * (which exposes a per-MODULE summary in {@code policies}), this DTO
 * carries every granule row in its raw shape so the drawer can render
 * MODULE/ACTION/REPORT entries with their concrete grants.
 *
 * <p>Read-after-write contract: the entries in {@code granules} are
 * exactly the rows that were written by the most recent successful
 * {@code PUT /api/v1/roles/{roleId}/granules} call (plus any other
 * granule rows that survive in the role aggregate). A drawer round-trip
 * "PUT then re-fetch" returns the same entries the user just saved.
 *
 * <p>Sort order is deterministic to keep the UI stable across reloads:
 * MODULE → ACTION → REPORT; within each type, alphabetic by {@code key}.
 *
 * <p>Granule rows are filtered to {@code rp.getPermission() == null}
 * (granule-shape rows). Legacy FK-shape permission rows continue to be
 * exposed via {@link com.example.permission.dto.access.AccessRoleDto}'s
 * {@code permissions} field.
 */
public record RoleGranulesDto(
        Long roleId,
        List<RolePermissionItemDto> granules
) {}
