package com.example.transcript.security;

import java.util.UUID;

/**
 * Resolved tenant scope + authz principal for an authenticated admin request.
 *
 * <p>{@code tenantId} is the canonical tenant scope (also the legacy
 * {@code tenant_id} / canonical {@code org_id}). {@code subject} is the request
 * authz principal — the same value the {@code @RequireModule} OpenFGA gate uses
 * (userId claim, falling back to {@code sub}) — and is what the KVKK m.12 access
 * audit records as {@code accessor_subject}. Both are context-derived; never
 * request-body supplied.
 */
public record AdminTenantContext(UUID tenantId, String subject) {
}
