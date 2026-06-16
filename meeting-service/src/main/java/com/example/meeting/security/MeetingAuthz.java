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
 *       {@code module:meeting} to read, or {@link #MANAGER}
 *       ({@code can_manage}) to mutate. Mirrors the endpoint-admin
 *       {@code module:endpoint-admin} contract — kebab-case object id,
 *       canonical {@code can_view}/{@code can_manage} relations matching
 *       the gitops OpenFGA seed.</li>
 *   <li><b>Object ReBAC</b> (Zanzibar owner/participant/viewer): on
 *       create of a {@code meeting:&lt;id&gt;} object the service writes an
 *       {@link #OWNER} tuple binding the creator. {@link #PARTICIPANT}
 *       and {@link #VIEWER_RELATION} are the other relations the OpenFGA
 *       meeting type exposes for per-meeting sharing.</li>
 * </ol>
 *
 * <p>Tuple shapes:
 * <pre>
 *   module gate : user:&lt;id&gt; # can_view|can_manage @ module:meeting
 *   object ReBAC: user:&lt;id&gt; # owner|participant|viewer @ meeting:&lt;uuid&gt;
 * </pre>
 *
 * <p>Per ADR-0012-EA (DD-EA-2) the canonical tuple writer for the module
 * gate is permission-service; meeting-service writes only the per-object
 * {@code meeting:&lt;id&gt;} owner tuple at create time (the service owns the
 * object it just created).
 */
public final class MeetingAuthz {

    /** Module object id — kebab/lowercase, matches the gitops OpenFGA seed. */
    public static final String MODULE = "meeting";

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
