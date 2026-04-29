package com.example.permission.model;

/**
 * Codex 019dd818 iter-16 (Plan C): role-level permission model marker.
 *
 * <p>iter-14 deduced the granule shortcut model by inspecting row shape
 * (permission_id NULL + type/key/grant present). That signal disappears when
 * a granule-managed role is replaced with an empty list — boot path then
 * misclassifies the role as legacy and re-seeds FK rows. The marker captures
 * the role-level intent explicitly and survives empty replace.
 *
 * <p><b>LEGACY</b>: role is seeded by {@link
 * com.example.permission.config.PermissionDataInitializer} via the
 * code-based FK flow. Legacy write endpoints
 * ({@code PUT /v1/roles/{id}/permissions},
 * {@code PATCH /v1/roles/{id}/permissions/bulk}) are valid.
 *
 * <p><b>GRANULE</b>: role is managed by {@code PUT /v1/roles/{id}/granules}.
 * Initializer must skip the seed flow on every boot. Legacy write endpoints
 * MUST return {@code 409 Conflict}.
 *
 * <p>Persisted as {@code roles.permission_model VARCHAR(20)} (see V17).
 */
public enum PermissionModel {
    LEGACY,
    GRANULE
}
