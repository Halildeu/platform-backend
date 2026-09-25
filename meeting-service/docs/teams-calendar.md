# Teams panel calendar bridge

The authenticated meeting API mediates user selection of an Outlook event;
the Teams worker control key and Microsoft organizer ID never come from the
browser. This feature is **disabled by default** and has not been deployed or
accepted against a real Microsoft tenant.

## Public route, existing gateway and authorization

All routes are below `/api/v1/admin/meetings/{meetingId}/teams-calendar`;
the existing meeting-service gateway route applies. The normal platform JWT
security chain, module `MEETING:can_manage` and canonical meeting `CAN_RECORD`
check are required for every request (including status and cancellation).
Read-only meeting access is insufficient. Unknown or foreign canonical meetings
retain the existing service's 404 behavior. Impersonation-broker/actor tokens
are refused. No caller-provided email, company, organizer or join URL grants access.
New selections additionally require a `SCHEDULED` or `IN_PROGRESS` canonical
meeting. `CANCELLED`/`COMPLETED` meetings cannot receive new selections; reading
or cancelling an already-owned schedule retains the normal permission check.

- `POST /events` body: `{ "from": "<ISO offset timestamp>", "to": "<ISO offset timestamp>" }`.
  Lists the user's organized Teams events within a maximum 31-day window and
  the next 90 days. Lists have up to 500 items and an explicit `truncated` flag.
  Opening the list does not select or join a meeting.
- `POST /schedule` body: `{ "eventId": "<opaque Graph event ID>" }`.
  Binds that event to the authorized canonical meeting and returns 202 with
  the schedule state. The backend creates a stable correlation reference.
  The worker reads current Graph timing; browser timing is never forwarded.
- `GET /schedule` reads the authenticated organizer's selection.
- `DELETE /schedule` cancels only a pending selection. It is not a hangup API.
  Dispatching/joined/terminal selections return conflict as defined by the worker.

Success responses are `no-store`. Internal token/control credentials, organizer
IDs, callback call IDs and upstream error bodies are not returned. 403 denies,
404 means no owned selection, 409 means conflict, 503 means unavailable. A
network timeout after a mutation does **not** prove the request failed; the
panel should read status before offering a retry. The transport never retries
a mutation automatically or follows redirects. Response bodies are limited to
1 MiB with strict JSON parsing and schema/scope validation.

## Identity and runtime configuration

`meeting.teams-calendar.enabled=false` by default. Before enabling, provide:

- `meeting.teams-calendar.worker-base-url`: operator-controlled private origin,
  e.g. `http://teams-capture-worker:8080`, with no path, query or credentials.
  HTTP is only for the approved private service network; use TLS where required.
- `meeting.teams-calendar.control-key`: secret-store value, at least 32 characters.
- `meeting.teams-calendar.microsoft-tenant-id`: approved Microsoft tenant UUID.
- Existing `meeting.assignee-directory` service-token settings, including the
  user-service origin and authorized `meeting-service` service credential.
- The user-service protected organizer resolver from backend #1193 enabled
  only after its broker/attribute conditions have been verified.
- Worker #1194 calendar-list/owner-scoped endpoints, configured Microsoft
  permissions and approved organizer allowlist.

The resolver receives the original validated issuer/subject only. Its returned
subject, tenant and positive directory IDs must match the expected identity;
numeric company claims, when present, must match the directory company. That
legacy company number is **not** compared to a synthetic hash of canonical
`org_id`. Meeting scope is enforced by the existing canonical recording check.

The identity client reuses the exact `users:internal` service-token implementation
with a separate bounded, no-redirect transport/cache. No new user password,
public worker route, global broker setting or Graph consent is introduced.

## Dispatch-time authorization

The backend persists a versioned identity binding with each selection: original
issuer/subject, canonical organization UUID, protected Microsoft tenant, stable
or directory-bound module principal, directory user and company IDs. The worker
stores no user bearer/password. The actor participates in immutable selection
equality. A new request cannot take over an existing schedule by changing actor.

After acquiring its Microsoft token and immediately before sending the Graph
create-call request, the worker calls
`POST /api/v1/internal/meetings/{meetingId}/teams-calendar/authorize` with an
auth-service SERVICE token. Audience is only `meeting-service`, client_id and
subject must both equal `teams-capture-worker`, and the exact permission is
`meeting:teams-schedule:authorize`. Other trusted services and admin user JWTs
cannot authorize this endpoint. It is not registered in local/dev profiles.

The endpoint freshly resolves the directory/Keycloak identity and requires its
subject, company, user, Microsoft tenant and organizer to still match selection.
It reads the meeting in its original canonical org, requires an active lifecycle,
and checks current module can_manage and meeting CAN_RECORD. These checks bypass
the local allow cache and request OpenFGA HIGHER_CONSISTENCY. Disabled/unavailable
policy service is not allow; an unavailable BLOCKED check cannot enable fallback.
Existing recorder checks retain their original behavior.

Worker prerequisites (secret values come only from the institution's secret store):

- `TeamsScheduleAuthorization__Enabled=true`
- `TeamsScheduleAuthorization__AuthServiceBaseUrl` (default `http://auth-service:8088`)
- `TeamsScheduleAuthorization__MeetingServiceBaseUrl` (default `http://meeting-service:8097`)
- `TeamsScheduleAuthorization__ClientSecret`: separate worker credential, 32+ characters.
- Auth-service `SERVICE_CLIENT_TEAMS_CAPTURE_WORKER_SECRET` with the same credential.
  The checked-in registration is blank/disabled until provisioned and grants only
  the explicit audience/permission above. Runtime global mint permission overrides
  must also include `meeting:teams-schedule:authorize`.
- Meeting-service service JWKS/issuer/audience configured and
  `MEETING_INTERNAL_SERVICE_JWT_CLIENT_IDS` extended with `teams-capture-worker`,
  preserving the existing authorized callers. Do not replace the existing list.

Both private service origins are operator-controlled; HTTP requires the approved
private service network. No redirects or unbounded responses are accepted. Token
minting and authorization share a 25-second deadline and never log credentials.
Explicit denial ends the schedule; unavailable service defers within the join
window. These pre-send outcomes release the unsent reservation. Once Graph POST
begins, ambiguous failures retain the reservation and never blindly retry.
The time window is rechecked after authorization. Pending actorless old snapshots
are retained and marked `actor_missing_requires_reselection`; dispatching old
snapshots retain reconciliation, without another create-call request.

The check is not an atomic transaction with remote Microsoft Graph, nor does it
automatically eject an already-joined bot after a later permission change.
Deploy compatible meeting-service/auth-service before this worker; enable the
user feature only after actual service-token and revocation smoke tests. Do not
roll back to a worker that lacks this guard while leaving scheduling enabled.

## Acceptance still outstanding

Local tests cover the actual MVC security/module chain with mocked directory,
worker and recording service, plus HTTP contracts and existing recording
authorization tests. They do not prove real Keycloak/Graph connectivity.

Panel event-picker integration is in platform-web #1198. Dispatch authorization
has controlled local tests; real service-token provisioning, TEST deployment and
tenant end-to-end acceptance must still be completed before user activation.

TEST deployment, Azure Bot/calling setup, native Teams live audio, temporal
named-speaker mapping and during-meeting analysis acceptance remain separate
open requirements. This bridge does not provide those capabilities.
