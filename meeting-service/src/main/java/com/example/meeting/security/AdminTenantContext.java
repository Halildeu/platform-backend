package com.example.meeting.security;

import java.util.UUID;

/**
 * Resolved tenant + subject for an authenticated admin request.
 * {@code tenantId} is the caller's canonical org scope (used as the
 * effective-org filter); {@code subject} is the authenticated principal
 * written into audit columns.
 *
 * <p>{@code authzPrincipal} is the principal used for OpenFGA object-ReBAC
 * tuple writes (e.g. the {@code owner @ meeting:<id>} tuple on create). It
 * MUST match the principal the {@link com.example.meeting.config.MeetingRequireModuleInterceptor}
 * module gate authorizes with (userId claim if present, else {@code sub}) —
 * otherwise the owner tuple would be written for a different subject than the
 * one the gate checks, so the creator could pass the module gate yet not own
 * the object they just created. It is distinct from {@code subject}, which is
 * the human-readable audit identity (sub / email / preferred_username).
 */
public record AdminTenantContext(UUID tenantId, String subject, String authzPrincipal) {
}
