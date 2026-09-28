# Platform user to Microsoft organizer binding

The calendar bridge must derive the organizer from the validated platform
user, not from a browser-supplied email, Teams display name or organizer ID.
The new internal user-service endpoint is the first part of that bridge:

`POST /api/users/internal/microsoft-organizer/resolve`

Request: `{ "issuer": "<validated JWT issuer>", "subject": "<validated JWT sub>" }`.
Authentication: the existing server-only service token with `users:internal`.
A platform user JWT is refused even if it contains the same authority string.
Never expose this service token or the worker's control key to the panel.

The endpoint requires an exact active, non-deleted directory subject with an
explicit company, an active exact Keycloak user, single-valued `entra_tid` and
`entra_oid`, the configured Microsoft tenant and a current federated link to
the configured broker. It reads only; it never backfills or links by email.
The broker's opaque `userId` is NOT used as the Graph object ID.

200 returns only `userId`, `companyId`, `subject`, `tenantId`, `organizerId`.
400 means malformed subject; 403 means not linked/allowed; 503 means disabled
or directory unavailable. Responses are no-store. The calling bridge must
also check the returned company against its authoritative meeting scope and
authorize the user's recording/managing rights before scheduling or cancelling.
Identity resolution alone grants no Graph or meeting access.

## Configuration and prerequisites

Disabled by default. user-service settings (Spring relaxed environment binding):

- `TEAMS_ORGANIZERIDENTITY_ENABLED=true`
- `TEAMS_ORGANIZERIDENTITY_ISSUER=https://<approved-keycloak-host>/realms/<realm>`
- `TEAMS_ORGANIZERIDENTITY_TENANTID=<approved-Microsoft-tenant-UUID>`
- `TEAMS_ORGANIZERIDENTITY_PROVIDERALIAS=microsoft` (default)

Uses existing `keycloak.admin-api` transport/realm and credential. No new
administrative grant is made by this code. Exact user + federated-link reads
must be permitted for that institutional service account and tested in TEST.

Before enabling, verify the canonical GitOps `setup-m365-broker.sh` contract:
Microsoft `tid`/`oid` FORCE-mapped to `entra_tid`/`entra_oid`, both attributes
editable only by administrators. The configured issuer must be the issuer for
the same Keycloak realm read by this transport. Do not enable against realms
where a user can edit these attributes or self-link an untrusted IdP. No change
to the shared broker, its token mappers, or live realm is performed by this PR.

## Remaining end-to-end work

This endpoint does not list Outlook events or schedule a bot by itself. The
meeting-authorized backend proxy, private worker event-list/ownership bridge,
panel calendar picker, actual tenant permissions and TEST rollout remain open.
Tests with synthetic Keycloak responses verify identity boundaries, not an
actual Microsoft sign-in, mailbox permission or Teams participation.
