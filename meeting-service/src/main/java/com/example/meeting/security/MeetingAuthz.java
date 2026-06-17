package com.example.meeting.security;

/**
 * Meeting-service authz constants — Faz 24 (#410).
 *
 * <p>Two authz surfaces, both via OpenFGA (common-auth
 * {@code OpenFgaAuthzService}):
 *
 * <ol>
 *   <li><b>Module gate</b> (route-level, {@code @RequireModule}): the
 *       caller must hold {@link #VIEWER} ({@code can_view}) on
 *       {@code module:MEETING} to read, or {@link #MANAGER}
 *       ({@code can_manage}) to mutate. The object id is the UPPERCASE
 *       module key {@code MEETING} — identical to the permission-service
 *       catalog key, the {@code role_permissions.permission_key}, and the
 *       OpenFGA object id the prod writer path emits (ADR-0041 §5, Option A,
 *       Codex 019ed603). All four are the SAME string because the MODULE
 *       write path applies no case transform; this aligns with the core-module
 *       convention ({@code module:ACCESS}/{@code module:AUDIT}). NOTE: this is
 *       NOT the endpoint-admin {@code module:endpoint-admin} shape — that
 *       catalog-UPPER/object-lowercase split is a legacy exception whose
 *       auto-grant bridge is not wired, deliberately not copied here.</li>
 *   <li><b>Object ReBAC</b> (Zanzibar owner/participant/viewer): on
 *       create of a {@code meeting:&lt;id&gt;} object the service writes an
 *       {@link #OWNER} tuple binding the creator. {@link #PARTICIPANT}
 *       and {@link #VIEWER_RELATION} are the other relations the OpenFGA
 *       meeting type exposes for per-meeting sharing.</li>
 * </ol>
 *
 * <p>Tuple shapes:
 * <pre>
 *   module gate : user:&lt;id&gt; # can_view|can_manage @ module:MEETING
 *   object ReBAC: user:&lt;id&gt; # owner|participant|viewer @ meeting:&lt;uuid&gt;
 * </pre>
 *
 * <p>Per ADR-0012-EA (DD-EA-2) the canonical tuple writer for the module
 * gate is permission-service; meeting-service writes only the per-object
 * {@code meeting:&lt;id&gt;} owner tuple at create time (the service owns the
 * object it just created).
 */
public final class MeetingAuthz {

    /**
     * Module gate object id — UPPERCASE {@code MEETING}, identical to the
     * permission-service catalog key + {@code role_permissions.permission_key}
     * + the prod OpenFGA tuple object id (ADR-0041 §5, Option A). Distinct from
     * {@link #OBJECT_TYPE} below (the lowercase OpenFGA model TYPE name for
     * per-meeting ReBAC objects {@code meeting:<uuid>}).
     */
    public static final String MODULE = "MEETING";

    /** Module read relation — OpenFGA {@code module#can_view}. */
    public static final String VIEWER = "can_view";

    /** Module mutate relation — OpenFGA {@code module#can_manage}. */
    public static final String MANAGER = "can_manage";

    /** Per-meeting object type for ReBAC tuples ({@code meeting:<uuid>}). */
    public static final String OBJECT_TYPE = "meeting";

    /** Owner relation on a {@code meeting:<id>} object. */
    public static final String OWNER = "owner";

    /** Participant relation on a {@code meeting:<id>} object. */
    public static final String PARTICIPANT = "participant";

    /** Viewer relation on a {@code meeting:<id>} object. */
    public static final String VIEWER_RELATION = "viewer";

    private MeetingAuthz() {
    }
}
