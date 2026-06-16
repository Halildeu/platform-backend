package com.example.transcript.security;

/**
 * Transcript-service authz constants.
 *
 * <p>Module-level OpenFGA gate (ADR-0012 / ADR-0012-EA pattern, same shape as
 * {@code endpoint-admin-service}'s {@code EndpointAdminAuthz}):
 *
 * <ul>
 *   <li>{@link #MODULE} = {@code transcript} (kebab-case object id; the gitops
 *       OpenFGA tuple seed + live model use this exact instance name — an
 *       uppercase literal would NOT match the seed → all {@code @RequireModule}
 *       routes 403).</li>
 *   <li>{@link #VIEWER} = {@code can_view} — read-only (READ / LIST / SEARCH /
 *       EXPORT of transcript segments).</li>
 *   <li>{@link #MANAGER} = {@code can_manage} — mutation (create / update /
 *       delete of transcript segments).</li>
 * </ul>
 *
 * <p>Tuple shape: {@code user:<id># <relation> @module:transcript}.
 *
 * <p>ADR-0012-EA: the OpenFGA tuple WRITER is ONLY permission-service
 * (DD-EA-2 boundary). transcript-service is a consumer; it never writes tuples.
 */
public final class TranscriptAuthz {

    /** Module object id — kebab-case (gitops seed + live OpenFGA model contract). */
    public static final String MODULE = "transcript";

    /** Read-only access relation, OpenFGA model {@code module#can_view}. */
    public static final String VIEWER = "can_view";

    /** Mutation/management relation, OpenFGA model {@code module#can_manage}. */
    public static final String MANAGER = "can_manage";

    private TranscriptAuthz() {
    }
}
