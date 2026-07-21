package com.example.transcript.security;

import java.util.UUID;

/**
 * Resolved tenant scope + subject for an authenticated admin request.
 *
 * <p>{@code tenantId} is the canonical org scope (org_id / tenant_id / company).
 *
 * <p>{@code subject} is the STABLE OIDC {@code sub} — the durable identity
 * written to audit columns ({@code created_by_subject},
 * {@code accessor_subject}) and used for cross-service correlation. It never
 * falls back to email/preferred_username/userId, because those can change
 * during a user's lifetime and would break audit-trail lineage across services.
 *
 * <p>{@code authzPrincipal} is the compatibility principal used by the module
 * authorization gate — the {@code userId} claim if present, else {@code sub}.
 * The existing OpenFGA / module authz continues to use this so live rows keep
 * working; only the durable audit boundary is upgraded.
 *
 * <p>Faz 24 issue #824 — before this split, transcript-service treated
 * {@code userId}-first as the "subject", so the same human produced
 * {@code created_by_subject} on write and {@code accessor_subject} on read
 * that could DIVERGE when the numeric userId claim changed, breaking the
 * KVKK m.12 audit correlation with meeting-service.
 */
public record AdminTenantContext(UUID tenantId, String subject, String authzPrincipal) {
}
