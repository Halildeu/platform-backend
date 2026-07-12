package com.example.meeting.security;

import java.util.UUID;

/**
 * Resolved tenant + subject for an authenticated admin request.
 * {@code tenantId} is the caller's canonical org scope (used as the
 * effective-org filter); {@code subject} is the stable OIDC {@code sub}
 * written into audit columns and durable object-ownership tuples.
 *
 * <p>{@code authzPrincipal} is the compatibility principal used by the module
 * authorization gate (userId claim if present, else {@code sub}). Durable
 * Meeting object ownership uses {@code subject}, which resolves to the stable
 * OIDC {@code sub}; mutable numeric userId claims must not own objects.
 */
public record AdminTenantContext(UUID tenantId, String subject, String authzPrincipal) {
}
