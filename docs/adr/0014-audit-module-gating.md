# ADR-0014 — Audit Module Gating: AUDIT vs IMPERSONATION_AUDIT

> **Status**: Accepted (2026-05-10)
> **Context**: User Impersonation v1 (spec [2026-05-user-impersonation-v1-spec.md](../plans/2026-05-user-impersonation-v1-spec.md))
> **Implementation**: PR-D2 (this PR — supersedes PR-D #138 closed)
> **Cross-AI peer review**: Codex thread `019e10bf-5256-7b03-b108-5f6d5543e3ed` (iter-2 AGREE WITH AMENDMENTS, ready_for_impl=true)

## Context

The PR-D first attempt (#138, closed 2026-05-09) introduced a dedicated
`/api/audit/events/impersonation` endpoint and an `impersonation-audit-view`
flat permission. Codex peer review identified three P1 issues:

1. **Substring leak in `AuditEventService.listEvents()`**: filter logic
   `containsIgnoreCase("IMPERSONATION", action)` matched
   `NON_IMPERSONATION_*` and other non-canonical action codes containing
   the substring. The dedicated endpoint silently included rows it
   shouldn't, and conversely, when used to exclude on the generic feed,
   would also drop legitimate audit rows that happened to contain the
   word. Both directions break audit integrity.
2. **Module gate too broad**: the dedicated endpoint was guarded by
   `@RequireModule("AUDIT", "can_view")`. Any AUDIT viewer could read
   impersonation events even though the underlying threat surface
   (privilege escalation history, denylist hits, MFA-skipped admin
   sessions) is materially more sensitive than ordinary audit log.
3. **Inert permission constant**: PR-D seeded an `impersonation-audit-view`
   flat permission via `PermissionDataInitializer`, but the OpenFGA gate
   at runtime was still `AUDIT.can_view`. The constant was never read by
   any check. Governance debt with zero behavioral effect.

Plus a fourth leak surface noticed during PR-D2 design:

4. **Live SSE stream**: `/api/audit/events/live` is gated by
   `AUDIT.can_view` and re-publishes every recorded event. Without
   suppression, an AUDIT viewer subscribed to the SSE channel would
   receive `IMPERSONATION_*` events live — bypassing the dashboard's
   list/export gating entirely.

5. **id-shortcut leak**: `GET /api/audit/events?id=<id>` returns a
   single event by id without applying the action filter. An AUDIT
   viewer who guessed an impersonation event id would get the full
   record back.

## Decision

PR-D2 introduces **two-module gating with at-source impersonation
exclusion**:

### Module split

| Module | Audience | Granted by default to |
|---|---|---|
| `AUDIT` (existing) | Ordinary audit reviewers | Audit ops, security ops |
| `IMPERSONATION_AUDIT` (new) | Impersonation auditors only | ADMIN role (`can_manage` seeded) |

The new module uses the existing generic `type module` declaration in
`backend/openfga/model.fga` — **no model schema change**. Tuples shape:
`module:IMPERSONATION_AUDIT can_view|can_manage user:<id>`.

### Authoritative event classification

A single allowlist of 5 canonical impersonation action codes lives in
`com.example.permission.audit.ImpersonationActionPredicate` and mirrors
`ImpersonationAuditEventTypes` 1:1:

- `IMPERSONATION_STARTED`
- `IMPERSONATION_STOPPED`
- `IMPERSONATION_BLOCKED`
- `IMPERSONATION_FAILED`
- `IMPERSONATION_REVOKED`

All read paths consult the predicate — **substring matching is forbidden**.

### Scope-aware service layer

`AuditReadScope` enum (`GENERIC_AUDIT` / `IMPERSONATION_AUDIT`) flows
through every read method on `AuditEventService`:

- `listEvents(..., scope)` — filters by scope predicate
- `findByIdPage(id, scope)` — id-shortcut returns 404 when the loaded
  event is in the wrong scope (closes leak #5)
- `exportEvents(..., scope)` — payload built scope-aware
- `createExportJob(..., scope)` — scope embedded in job creation

### At-source live stream suppression

`AuditEventService.dispatchLiveEvent(...)` discards
`IMPERSONATION_*` events before publishing to the
`AUDIT.can_view`-gated SSE channel (closes leak #4). A separate
IMPERSONATION_AUDIT live stream may be added in a follow-up if needed
(per-emitter authz deferred per Codex iter-2 amendment).

### Strict action filter on dedicated endpoint

`/api/audit/events/impersonation?filter[action]=…` accepts only:
- `null` (absent) — returns all 5
- `"IMPERSONATION"` (alias) — returns all 5
- one of the 5 canonical codes — exact match

Anything else returns **400 Bad Request**. The previous "silently rewrite
to `IMPERSONATION`" behavior is removed.

### Granule-managed ADMIN seed

The flat `impersonation-audit-view` permission is **gone**. The
authoritative seed path is `PermissionDataInitializer.DEFAULT_ROLE_GRANULES`
which adds `MODULE:IMPERSONATION_AUDIT:MANAGE` to the ADMIN role. The
initializer publishes a `RoleChangeEvent` after seeding; the
`RoleChangeEventHandler` runs `TupleSyncService.propagateRoleChange()`
**after-commit** (BEFORE_COMMIT outbox + AFTER_COMMIT async) so the
OpenFGA tuple write happens outside the seed transaction.

Raw FGA tuples in `backend/openfga/tuples-seed.json` for feature gating
are **forbidden** (CNS-20260415-004). The granule seed → outbox →
async tuple write is the only sanctioned path.

## Consequences

### Positive

- **Defense in depth**: 5 leak channels closed (substring, broad gate,
  inert permission, live SSE, id-shortcut). Generic AUDIT feed is now a
  pure non-impersonation view by construction, not by hopeful filtering.
- **Audit integrity**: `NON_IMPERSONATION_*` rows are no longer
  accidentally dropped by the generic feed (substring matcher gone).
- **Governance**: granule-managed seed obeys the
  no-raw-tuple-seed rule; future role rollouts go through
  `AccessRoleService.updateRoleGranules` consistently.
- **Forward compat**: a new impersonation event type only requires
  extending `ImpersonationAuditEventTypes` + `ImpersonationActionPredicate.ALLOWED`
  (drift guard test catches misalignment).

### Negative / open work

- **Cross-org tenant scoping**: `IMPERSONATION_AUDIT.can_view` is global
  today. Multi-tenant SaaS deployments will need tenant-scoped guards
  (tuple `tenant:X#impersonation_audit_viewer@user:Y`). Out of PR-D2
  scope; tracked as security debt note in spec §3.4.5.
- **Initializer tuple propagation lag**: the granule seed runs in the
  bootstrap transaction; the OpenFGA tuple is written async via the
  outbox. ADMIN users may briefly fail an `IMPERSONATION_AUDIT.can_view`
  check immediately after a fresh DB rebuild. The outbox poller closes
  the gap within the poll cadence (seconds). Acceptable for bootstrap
  because the 6 ADMIN users are already organization admins (bypass
  takes effect immediately via `RequireModuleInterceptor.isOrganizationAdmin`).

### Alternatives considered

- **Option A (this ADR)**: New `IMPERSONATION_AUDIT` module + scope-aware
  service. Chosen.
- **Option B**: Keep `AUDIT` gate; add an action-level relation
  (`module:AUDIT impersonation_view@user:Y`). Rejected: requires OpenFGA
  model change (new relation on `module` type), bleeds the
  general-vs-special distinction into the generic feed, and complicates
  per-emitter authz design later.
- **Option C**: Move impersonation rows to a separate table. Rejected:
  reporting joins (`impersonation_sessions` ⋈ `permission_audit_events`)
  break, and the leak channels (substring, id-shortcut, live SSE) recur
  the moment any code path joins the table back into the audit feed.

## References

- Spec: [2026-05-user-impersonation-v1-spec.md §3.4](../plans/2026-05-user-impersonation-v1-spec.md)
- Implementation classes:
  - `permission-service/src/main/java/com/example/permission/audit/ImpersonationActionPredicate.java`
  - `permission-service/src/main/java/com/example/permission/audit/AuditReadScope.java`
  - `permission-service/src/main/java/com/example/permission/service/AuditEventService.java`
  - `permission-service/src/main/java/com/example/permission/controller/AuditEventController.java`
  - `permission-service/src/main/java/com/example/permission/config/PermissionDataInitializer.java`
- Codex peer review:
  - thread `019e10bf-5256-7b03-b108-5f6d5543e3ed` iter-1 REVISE (5 ana revize)
  - iter-2 AGREE WITH AMENDMENTS, `ready_for_impl=true` (5 amendment plana girer)
- Governance HARD RULE: CNS-20260415-004 (raw FGA tuple seed forbidden)
