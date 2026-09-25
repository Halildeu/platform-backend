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

## Acceptance still outstanding

Local tests cover the actual MVC security/module chain with mocked directory,
worker and recording service, plus HTTP contracts and existing recording
authorization tests. They do not prove real Keycloak/Graph connectivity.

Panel event-picker integration, future dispatch-time recording-right revalidation,
and tenant end-to-end acceptance must be completed before user activation.
The worker currently persists the selected organizer/event; it does not yet
persist and revalidate the platform actor's recording grant at dispatch time.
Do not represent this request-time authorization as future revocation support.

TEST deployment, Azure Bot/calling setup, native Teams live audio, temporal
named-speaker mapping and during-meeting analysis acceptance remain separate
open requirements. This bridge does not provide those capabilities.
