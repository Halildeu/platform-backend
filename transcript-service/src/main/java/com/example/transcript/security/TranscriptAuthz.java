package com.example.transcript.security;

/**
 * Transcript-service authz constants.
 *
 * <p>Module-level OpenFGA gate (ADR-0012 / ADR-0012-EA pattern, same shape as
 * {@code endpoint-admin-service}'s {@code EndpointAdminAuthz}):
 *
 * <ul>
 *   <li>{@link #MODULE} = {@code TRANSCRIPT} (UPPERCASE object id; identical to
 *       the permission-service catalog key, the {@code role_permissions.permission_key},
 *       and the OpenFGA object id the prod writer path emits — ADR-0041 §5,
 *       Option A, Codex 019ed603. The MODULE write path applies no case
 *       transform, so catalog key == granule key == object id == this literal,
 *       matching the core-module convention {@code module:ACCESS}/{@code module:AUDIT}.
 *       The gitops test seed + live model are flipped to {@code module:TRANSCRIPT}
 *       in the same promotion bundle (staged re-seed) so test and prod agree.</li>
 *   <li>{@link #VIEWER} = {@code can_view} — read-only (READ / LIST / SEARCH /
 *       EXPORT of transcript segments).</li>
 *   <li>{@link #MANAGER} = {@code can_manage} — mutation (create / update /
 *       delete of transcript segments).</li>
 * </ul>
 *
 * <p>Tuple shape: {@code user:<id># <relation> @module:TRANSCRIPT}.
 *
 * <p>ADR-0012-EA: the OpenFGA tuple WRITER is ONLY permission-service
 * (DD-EA-2 boundary). transcript-service is a consumer; it never writes tuples.
 */
public final class TranscriptAuthz {

    /**
     * Module gate object id — UPPERCASE {@code TRANSCRIPT}, identical to the
     * permission-service catalog key + {@code role_permissions.permission_key}
     * + the prod OpenFGA tuple object id (ADR-0041 §5, Option A).
     */
    public static final String MODULE = "TRANSCRIPT";

    /** Read-only access relation, OpenFGA model {@code module#can_view}. */
    public static final String VIEWER = "can_view";

    /** Mutation/management relation, OpenFGA model {@code module#can_manage}. */
    public static final String MANAGER = "can_manage";

    private TranscriptAuthz() {
    }
}
