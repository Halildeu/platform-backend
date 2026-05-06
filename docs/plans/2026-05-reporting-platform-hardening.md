# Reporting Platform Hardening — Project Plan v2

| Field | Value |
|---|---|
| **Status** | DRAFT v2 — Claude self-review applied (8 corrections) |
| **Owner** | Platform Reporting (Halil Koçoğlu) |
| **Plan-time consensus** | Codex iter 11–15 + Claude self-review iter-16 |
| **Initiated** | 2026-05-06 |
| **Target completion** | 2026-05-20 (~3 phases, 8 PRs) |
| **Tracking doc** | this file |
| **Related repos** | `Halildeu/platform-backend`, `Halildeu/platform-web` |

## Changelog (v1 → v2)

Claude self-review (iter-16) applied 8 corrections to the original v1 plan:

1. **PR-0 promoted** — server-side grouping is the user's P0 bug, no longer buried inside PR-4b
2. **Phased delivery** — Phase 1 (user-visible) before Phase 2/3 (deep hardening)
3. **Sprint-end Definition of Done** — concrete SLA targets in Section 9
4. **Rollback Plan** — explicit subsection on every PR
5. **Schema-service availability spec** — concrete fallback chain in Section 7
6. **Learnings log** — Section 13 captures per-PR lessons
7. **PR-0 sub-breakdown** — split into PR-0a (frontend wiring), PR-0b (backend SQL)
8. **Release notes ritual** — every PR updates `CHANGELOG.md`

---

## 1. Vision

> Stable, healthy, flexible, performant, long-lived reporting infrastructure for Workcube ERP integration.
>
> - One report UI, many backend tables — flexible composition
> - Backend filter/sort/grouping correctness proven against real MSSQL
> - Grid visual identical across all reports; per-report action menu
> - Single filter entry, single action entry
> - Tenant isolation enforced at both build-time and runtime
> - Schema truth from a single authoritative source (`schema-service`)
> - **Phased delivery: user-visible value in week 1, deep hardening through week 3**

## 2. Why this plan exists

The 4-iter loop on `fin-muhasebe-detay` (PRs #69, #70, #73, #74) exposed structural weaknesses:

- Report JSON contract has no semantic validator — wrong `rowFilter.column` (counterparty vs tenant id) shipped to production silently
- `schemaMode`, `sourceSchema`, `tenantBoundary` relationships are not CI-enforced
- `sourceQuery` and `columns[]` projections are not cross-checked
- Tenant boundary lives in tribal knowledge, not in code
- Errors surface only when users report them (no per-report metric)
- **Server-side grouping is sent by frontend but ignored by backend** (user-reported regression — PR-0 fix)

The plan addresses these gaps in 8 programs delivered as 8 sequential PRs across 3 phases.

## 3. Programs

### 3.1 Program 1 — Report Contract Gate (build-time)

**Outcome:** New or changed report definitions pass schema + semantic validation in CI before merge.

**Deliverables:**
- `report-service/src/main/resources/reports/report-definition.schema.json` (Draft 2020-12)
- `report-service/src/main/resources/reports/tenant-column-allowlist.json`
- `report-service/src/main/resources/reports/exceptions.json` (`id`, `ruleIds`, `reason`, `owner`, `expiresAt`)
- Java validator package: `com.example.report.contract.*`
- 11 validator rules (RC-001..011)
- `ReportDefinitionContractTest` integrated into `mvn -pl report-service test`
- `target/report-contract-summary.md` artifact (PR-readable)
- ADR: `docs/adr/0006-report-definition-contract.md`

**New schema fields on every report JSON:**
```json
"contractVersion": 1,
"schemaMode": "yearly | current | canonical | static",
"tenantBoundary": {
  "mode": "schema | row | none",
  "scopeType": "COMPANY",
  "schemaResolver": "workcube-year-company | workcube-current-company | none",
  "schemaPattern": "workcube_mikrolink_{year}_{companyId}",
  "reason": "string"
}
```

**`schemaMode` enum (4 values):**
- `yearly` → `workcube_mikrolink_{year}_{companyId}` (yearly fact tables)
- `current` → `workcube_mikrolink_{companyId}` (per-tenant current state) — NEW
- `canonical` → `workcube_mikrolink` (master/lookup)
- `static` → exception only (legacy hardcoded)

**Validator rules (RC-001..011):**

| ID | Rule | Severity |
|---|---|---|
| RC-001 | `schemaMode=yearly` requires `yearColumn` | FAIL |
| RC-002 | yearly + custom `sourceQuery` requires `[{schema}]` placeholder | FAIL |
| RC-003 | hardcoded `workcube_mikrolink_YYYY_ID` + `static` mode forbidden | FAIL Tier 0; WARN Tier 1+ |
| RC-004 | `rowFilter.scopeType=COMPANY` column must be in per-table allowlist; column existence verified via schema-service snapshot | FAIL |
| RC-005 | `tenantBoundary.mode=schema` + non-empty `rowFilter` is forbidden (boundary clarity) | FAIL |
| RC-006 | `tenantBoundary.mode=none` reports cannot reference tenant fact tables | FAIL |
| RC-007 | `columns[].field` projections must exist in `sourceQuery` SELECT (heuristic) | WARN |
| RC-008 | `schemaResolver` value must be in registered list | FAIL |
| RC-009 | `action.scope` must be `grid \| row \| selection` | FAIL |
| RC-010 | destructive actions require non-null `permission` and `confirmation` | FAIL |
| RC-011 | (TS-level) `action.handler` must be async — enforced at TypeScript level, not in JSON | FAIL |

---

### 3.2 Program 2 — Runtime Tenant Guard

**Outcome:** Tenant boundary enforced inside the request path, not just at build-time.

**Deliverables:**
- `TenantBoundaryGuard` preflight component
- `CurrentTenantSchemaResolver` (NEW resolver for `workcube_mikrolink_{companyId}` pattern)
- Hardening of `YearlySchemaResolver`:
  - No `def.sourceSchema()` fallback for `tenantBoundary.mode=schema` reports
  - `extractCompanyFromSchema()` only as legacy exception
  - Super-admin without header on tenant-bound report → 400 (selection required)
- Schema existence check via `schema-service` (`/api/v1/schema/snapshot`)

**Failure semantics:**
- 400 — header missing on tenant-bound report (multi-company / super-admin); single-company users auto-selected
- 403 — selected company outside user scope (existing PR #70 behaviour)
- 503 — resolved schema does not exist + `report_resolved_schema_miss` metric increment
- Frontend MUST NOT silent-fallback; surface error state and toast

---

### 3.3 Program 3 — Action Menu Standard

**Outcome:** Every grid offers actions in a consistent visual location; content varies per report.

**Deliverables:**
- `EntityGridTemplate` API extension: `actions?: ReportAction<TRow>[]`
- `ReportModule.actions?` field on the module type (mfe-reporting)
- Three render slots driven by `scope`:
  - `grid` → toolbar `[Eylemler ▾]`
  - `row` → AG Grid pinned-right action column with three-dot menu
  - `selection` → `[Seçili (N) ▾]` toolbar shown when rows selected
- A11y: `role="menu"`, `role="menubutton"`, keyboard navigation, `aria-label`
- Confirmation modal for destructive actions
- Permission semantics:
  - `permission: string | null` — `null` = inherit report-view permission
  - `permission: undefined` → config bug, fail in dev/test, hidden + warn in production
  - destructive actions cannot have `permission: null`
- `requiresSingleCompany` shows action as disabled with tooltip when not satisfied
- New metric: `report_action_invoked_total{report, action_id, status}`

---

### 3.4 Program 4 — Operational Observability

Same as v1 — metrics + structured log + 3 alerts. See Section 9.3.

---

### 3.5 Program 5 — Grid Governance

Same as v1 — ADR + ESLint allowlist + context-health migrate + AuditEventFeed spike (NOT migration).

---

### 3.6 Program 6 — Filter / Sort / Grouping Backend Correctness

**Outcome:** AG Grid filter/sort/grouping requests produce semantically correct T-SQL on real MSSQL, with property-based protection against SQL injection.

#### 6a — Filter / Sort / Null / Injection Matrix
- Generated parameterized cases (~50–70)
- Property-based SQL injection tests (jqwik)
- Sort: multi-column, NULL ordering, Turkish collation
- Multi-year UNION ALL test
- MSSQL Testcontainers integration with deterministic seed
- `@Tag("integration")` profile (separate Maven invocation)

#### 6b — Server-Side Grouping (proper implement) — NOW DELIVERED IN PHASE 0 (PR-0)
**See PR-0 in Section 4.0.** This was promoted from PR-4b to PR-0 during v2 self-review because it is a user-reported P0 production regression.

---

### 3.7 Program 7 — Multi-table Composition Governance

Same as v1 — `JoinFragmentRegistry` + `ColumnTypeRegistry`, vetted-id based, NOT dynamic composer.

---

### 3.8 Program 8 — Schema Truth Integration (cross-cutting)

**Outcome:** Schema metadata has one authoritative source: `schema-service`.

**Build-time:**
- Validator loads canonical snapshot from `docs/migration/workcube-schema.json` (already committed)
- Optional CI step pulls a fresh snapshot from schema-service before validator runs

**Runtime backend:**
- `FilterTranslator` is column-type-aware, type fetched from schema-service
- `TenantBoundaryGuard` calls `schema-service` `schemaExists()` before executing tenant-bound queries
- `SqlBuilder` uses column type for grouping/aggregation choices

**Runtime frontend:**
- `useReportSchemaContext` hook (already exists) used for AG Grid `colDef` enrichment
- Filter type picked automatically from column data type (text → `agTextColumnFilter`, etc.)

**Schema-service availability fallback (R-3 mitigation, expanded from v1):**
1. `schema-service` is the primary source at runtime, with **5-min Caffeine cache** (`schemaSnapshot` cache name)
2. On cache miss + service unreachable: fall back to **committed snapshot file** (`docs/migration/workcube-schema.json`)
3. On both unavailable: fall back to **`columns[].type` from report registry JSON** with WARN log + `schema_truth_fallback_total{tier}` metric increment
4. Build-time validator always uses committed snapshot (deterministic CI)
5. Snapshot file age >30 days → CI warn (refresh runbook)

## 4. Sprint Plan — 3 Phases, 8 PRs

### 4.0 Phase 0 — User-Visible Stabilization (~3 days)

**Outcome:** User's reported P0 bug fixed; user sees value before deeper hardening starts.

| PR | Title | Effort | Notes |
|---|---|---|---|
| **PR-0a** | Frontend: forward `rowGroupCols` / `groupKeys` / `valueCols` to backend | 0.5 d | `GridRequest` extension + `dynamic-report/api.ts` forward |
| **PR-0b** | Backend: server-side `GROUP BY` + aggregation in `QueryEngine` + `SqlBuilder` | 2 d | drill levels, aggregation operators, multi-level expand, NULL bucket, 50k group cap |

**PR-0 Acceptance:**
- User opens muavin → drags Proje Adı to group panel → grid collapses to one row per project
- Expanding a group lazy-loads child rows
- Multi-level drill (Şube > Departman) works
- NULL group key bucket displayed deterministically
- Aggregations (SUM/AVG/COUNT) shown in group rows where columns have `aggFunc`
- Integration test (Testcontainers MSSQL) covers single-level + multi-level + NULL + aggregation
- Production smoke: muavin grouping returns expected row counts for 3 different reports
- No regression in non-grouped queries (existing flat list behaviour preserved)

**PR-0 Rollback Plan:**
- Frontend: feature flag `reporting.serverSideGrouping.enabled = true` (default true after deploy)
- Backend: same feature flag (`@Value("${report.grouping.serverSide.enabled:true}")`)
- If grouping breaks production: set flag false → backend ignores `rowGroupCols`, frontend falls back to flat list (same as today)
- Hotfix path: <1h to revert image digest

### 4.1 Phase 1 — Foundation (~4 days)

| PR | Title | Programs | Effort |
|---|---|---|---|
| **PR-1** | Tenant Contract Gate + Runtime Guard (combined) | 1 + 2 + 8 partial | 2.5 d |
| **PR-2** | Action Menu Standard | 3 | 1 d |
| **PR-3** | Observability + Grid Governance MVP | 4 + 5 | 1.5 d |

### 4.2 Phase 2 — Deep Correctness (~4–6 days)

| PR | Title | Programs | Effort |
|---|---|---|---|
| **PR-4a** | Filter/sort/null/injection matrix + column header type-awareness | 6a + 8 partial | 2 d |
| **PR-5** | JoinFragmentRegistry + ColumnTypeRegistry + composition validator | 7 + 8 partial | 2–3 d |
| **PR-6** | HR / satış / stok migration wave + exceptions burn-down | follow-up | 1.5 d |

**Total:** 11–13 working days end-to-end with Codex peer review at every step.

**Why phased delivery:**
- Phase 0 fixes user-reported P0 bug in ~3 days (was 9 days in v1)
- Phase 1 delivers contract gate + action menu + observability MVP (foundation)
- Phase 2 delivers filter/sort/composition correctness (deep hardening)

## 5. Migration Tier (existing 27 reports)

| Tier | Count | Pattern | Blocking when |
|---|---:|---|---|
| **0** Tenant fact, ready | 5 | yearly + custom sourceQuery + rowFilter cleared (PR #74) | PR-1 |
| **B** Tenant fact, working with substitution | 9 | yearly + `sourceSchema=workcube_mikrolink_1` + yearColumn | Tier 1 batch (PR-6) |
| **C** Tenant fact, hardcoded year+id | 4 | yearly + `sourceSchema=workcube_mikrolink_2026_1` + yearColumn | Tier 1 batch (PR-6) |
| **D** HR | 9 | `mode=null` + `sourceSchema=workcube_mikrolink` | **Owner clarification needed** |
| **E** satis-ozet + stok-durum | 2 | hardcoded tenant-1 schema + `mode=null` | **Owner clarification needed** |
| **F** Canonical / lookup | 1 | `fin-butce-gerceklesen` master schema | Reviewed in PR-1 |

Reports outside Tier 0 stay in **warn-only** mode in Program 1 until they pass through PR-6.

## 6. Open Decisions

| # | Decision | Default | Owner | Required by |
|---|---|---|---|---|
| **D-1** | PR-0 grouping approach | proper implement (Codex iter-15 + Claude self-review) | resolved → execute | PR-0 kickoff |
| **D-2** | HR reports (9): tenant-specific or master? | TBD | Owner | PR-6 kickoff |
| **D-3** | satis-ozet + stok-durum tenant-specific? | Likely yes (current `sourceSchema` is BUG) | Owner | PR-6 kickoff |
| **D-4** | Multi-customer SaaS model | Defer; document `CustomerContext` contract | Architect | post-PR-6 |
| **D-5** | Cross-tenant admin reports | Default off; opt-in via `tenantBoundary.mode="multiTenantAggregate"` | Architect | post-PR-6 |

PR-0, PR-1, PR-2, PR-3, PR-4a, PR-5 do NOT depend on D-2 / D-3 — they can proceed in parallel with the open questions.

## 7. Risk Register (with concrete mitigations)

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| R-1 | Validator false-positives block legitimate reports | Med | Med | Tier 1+ warn-only initially; expand blocking gradually; feature flag `report.contract.validator.enabled` |
| R-2 | MSSQL Testcontainers slow CI | Med | Low | Separate `@Tag("integration")` profile; baseline run nightly + on-demand |
| R-3 | `schema-service` unavailable at runtime | Low | High | 3-tier fallback: 5-min Caffeine cache → committed snapshot file → `columns[].type` registry; metric `schema_truth_fallback_total{tier}`; never block query (degraded mode) |
| R-4 | Action Menu permission default ambiguity | Low | High | Explicit `null` vs `undefined` semantics; destructive actions cannot have `null` |
| R-5 | AuditEventFeed migration regression | High | High | NO migration in this plan; spike-only deliverable |
| R-6 | YearlySchemaResolver `sourceSchema` fallback removal breaks legacy reports | Med | Med | Migration tier; only Tier 0 + new/changed enforced first; feature flag `report.tenantGuard.strict` |
| R-7 | i18n key drift if locale dictionary merged | High | Med | NOT done; leave app-specific keys |
| **R-8** | **PR-0 grouping regression on existing non-grouped queries** | **Low** | **High** | **Feature flag (`report.grouping.serverSide.enabled`); backend ignores `rowGroupCols` when off; one-line revert path** |
| **R-9** | **Plan velocity drift (PRs slip)** | **Med** | **Med** | **Section 12 status table updated per PR; phase-end retrospective; owner notified if a phase exceeds estimate by >30%** |

## 8. Performance Guarantees (asserted in every PR)

- SQL query timeout: 30 s (Hikari)
- Slow query log threshold: 2 s warn / 10 s error
- Per-report cache (Caffeine) optional; companyOptions 5 min TTL is the reference pattern
- AG Grid SSR `cacheBlockSize=50`
- Frontend lazy chunks + Module Federation share for grid + ag-grid
- Production p95 alerting (deferred to ops integration)
- NO JMH micro-benchmark in V1 (real bottleneck is SQL plan, not Java translation)

## 9. Stability Guarantees

### 9.1 Per-PR
- Cross-AI peer review (HARD RULE 2026-05-05): every PR reviewed by Codex
- Build-time validator + runtime guard (double gate)
- ADR + exception registry for audit trail
- Schema truth single source: `schema-service` + committed snapshot
- Per-report metric for early bug detection
- Migration tier: warn-only Tier 1+, fail-fast Tier 0 + new/changed
- MSSQL Testcontainers covers integration semantics
- **Rollback plan documented per PR (see each PR section)**
- **`CHANGELOG.md` updated with user-visible note (release notes ritual)**

### 9.2 Sprint-end Definition of Done

The reporting platform is considered "hardened" when the following targets are met for **7 consecutive days** after the last PR merge:

| Metric | Target |
|---|---|
| `report_query_total{sqlState="207"}` (Invalid object) | 0 |
| `report_query_total{sqlState="208"}` (Invalid column) | 0 |
| Per-report `error_rate` | < 1% |
| Per-yearly-report `p95 latency` | < 3 s |
| Per-current-report `p95 latency` | < 1 s |
| Tier 0 reports passing 11 RC rules | 5 / 5 |
| Tier 1 reports identified in warn-only state | ≥ 22 |
| Server-side grouping working across reports | ≥ 3 reports verified |
| `AuditEventFeed` capability gap document | published |
| User-reported reporting bugs (last 7 days) | < baseline |

If any target is missed at sprint-end, that target becomes the next sprint's first PR.

### 9.3 Observability deliverables (Program 4)

**Prometheus metrics:**
- `report_query_total{report, schemaMode, status, sqlState}`
- `report_query_duration_seconds{report, schemaMode}`
- `report_query_rows{report}` histogram
- `report_resolved_schema_miss{report}`
- `report_company_header_narrow_total{status}`
- `report_action_invoked_total{report, action_id, status}`
- `schema_truth_fallback_total{tier}` (R-3 mitigation)

**Structured log fields:** `report`, `userId`, `companyId`, `schemaMode`, `resolvedSchemas`, `rowCount`, `durationMs`, `errorCode`

**Initial alerts (3):**
- SQL state 207/208 spike
- Per-report `error_rate` > threshold
- `report_resolved_schema_miss` > threshold for tenant-bound reports

### 9.4 Release Notes Ritual

Every PR updates `platform-backend/CHANGELOG.md` (or repo-root `CHANGELOG.md`, depending on convention) with one user-visible note in the appropriate Phase section. Format:

```
## [Unreleased] — Phase 0 (User Stabilization)

### Added
- Server-side grouping in reports (PR-0). Drag a column to the group panel
  and rows collapse to one row per group; expand to drill in.

### Fixed
- Grouping no longer shows ungrouped raw rows when a column is dragged to
  the group panel (PR-0).
```

After each phase ends, the section is renamed `## [vX.Y] — yyyy-mm-dd` and a new `[Unreleased]` block is created.

## 10. Don't-Do List (Codex consensus)

- ❌ `createGridConfig()` helper — abstraction debt
- ❌ Locale dictionary merge — i18n breaking risk
- ❌ AuditEventFeed migration without spike — capability gap
- ❌ H2 in-memory test DB — T-SQL dialect divergence
- ❌ Full dynamic `ReportComposer` — production plan instability
- ❌ Big-bang Tier 1 migration — CI lock risk
- ❌ Cross-tenant report by default — admin-only opt-in
- ❌ JMH micro-benchmarks in V1 — wrong layer
- ❌ 140 manual filter test cases — 50–70 generated cases sufficient
- ❌ Multi-customer schema namespace expansion — DB-per-customer preferred
- ❌ Both ESLint + runtime contract guard — ESLint sufficient
- ❌ Capability surface full V1 migration — `actions?` leaf to start

## 11. Per-PR Acceptance Checklists

Each PR section below carries its own acceptance checklist + rollback plan.

### 11.0 PR-0 — Server-Side Grouping (Phase 0)

**Acceptance:**
- [ ] Branch `feat/server-side-grouping` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] PR-0a: `GridRequest` type extends `rowGroupCols`, `groupKeys`, `valueCols`, `pivotMode`, `pivotCols`
- [ ] PR-0a: `dynamic-report/api.ts` forwards new fields
- [ ] PR-0b: `ReportController.getData` accepts new params
- [ ] PR-0b: `QueryEngine.executeQuery` distinguishes group level vs leaf level
- [ ] PR-0b: `SqlBuilder` emits `GROUP BY` with parent `groupKeys` filter
- [ ] PR-0b: aggregation operators implemented (SUM/AVG/COUNT/MIN/MAX)
- [ ] Multi-level drill verified
- [ ] NULL group key bucket displayed
- [ ] 50k group cap enforced (`503 grouping_too_large`)
- [ ] Integration test (Testcontainers MSSQL) covers single + multi + NULL + aggregation
- [ ] Feature flag `report.grouping.serverSide.enabled` (default true)
- [ ] Frontend feature flag mirror via shell config
- [ ] No regression on flat (non-grouped) queries
- [ ] Codex peer review — AGREE
- [ ] Cluster smoke: muavin grouping returns expected row counts
- [ ] `CHANGELOG.md` updated with user-visible note
- [ ] Section 12 status table updated

**Rollback Plan:**
- Set `report.grouping.serverSide.enabled=false` via ConfigMap → kubectl rollout restart
- Backend ignores grouping params, frontend falls back to flat list
- If feature flag missed, revert pod image digest (one-line operation)
- Total rollback time: <5 min

### 11.1 PR-1 — Tenant Contract Gate + Runtime Guard (Phase 1)

**Acceptance:**
- [ ] Branch `feat/report-contract-gate-and-tenant-guard` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] `report-definition.schema.json` (Draft 2020-12) committed
- [ ] `tenant-column-allowlist.json` committed
- [ ] `exceptions.json` skeleton committed
- [ ] Java validator package: 11 RC rules implemented
- [ ] `CurrentTenantSchemaResolver` implemented
- [ ] `YearlySchemaResolver` fallback hardened (no `def.sourceSchema()` for tenant-bound)
- [ ] `TenantBoundaryGuard` implemented and wired into `QueryEngine`
- [ ] `ReportDefinitionContractTest` integrated into `mvn -pl report-service test`
- [ ] `target/report-contract-summary.md` produced
- [ ] 5 Tier 0 reports carry explicit `tenantBoundary`
- [ ] Tier 1+ reports infer `tenantBoundary` (warn-only)
- [ ] ADR `docs/adr/0006-report-definition-contract.md` written
- [ ] Schema-service `schemaExists()` integration smoke-tested
- [ ] All existing tests still pass (`mvn -pl report-service test`)
- [ ] Codex peer review — AGREE
- [ ] Cross-AI HARD RULE: implementer ≠ reviewer
- [ ] Cluster smoke: muavin still works for super-admin + scoped users
- [ ] No `Invalid column` errors in 24 h after deploy
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- Set `report.contract.validator.enabled=false` → CI skips contract test
- Set `report.tenantGuard.strict=false` → resolver falls back to legacy `def.sourceSchema()` behaviour
- Worst case: revert pod image digest

### 11.2 PR-2 — Action Menu Standard (Phase 1)

**Acceptance:**
- [ ] Branch `feat/grid-action-menu-standard` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] `EntityGridTemplate` exposes `actions?: ReportAction<TRow>[]`
- [ ] `ReportModule.actions?` field added
- [ ] Three render slots wired (grid / row / selection)
- [ ] Empty-set rule: hidden when no actions
- [ ] `requiresSingleCompany` disabled + tooltip
- [ ] A11y: role/aria/keyboard nav in `EntityGridTemplate.contract.test.tsx`
- [ ] Confirmation modal for destructive actions
- [ ] Permission semantics: `null` vs `undefined` enforced (destructive cannot be `null`)
- [ ] Two example actions wired on a real report (one grid, one row)
- [ ] Storybook stories for each scope
- [ ] Metric `report_action_invoked_total` registered
- [ ] Codex peer review — AGREE
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- ReportModule.actions is opt-in; not setting it preserves current behaviour
- If serious bug: revert frontend bundle to previous BUILD_SHA

### 11.3 PR-3 — Observability + Grid Governance MVP (Phase 1)

**Acceptance:**
- [ ] Branch `feat/observability-grid-governance` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] Prometheus metrics registered (Section 9.3)
- [ ] Structured query log fields wired
- [ ] Three alerts configured (config or Grafana JSON)
- [ ] ADR `docs/adr/0008-grid-component-canonical.md` written
- [ ] ESLint `no-restricted-imports`: `apps/**` cannot import `ag-grid-react` (allowlist exceptions)
- [ ] Allowlist documented (AuditEventFeed, context-health, hr-compensation chart, design-lab, internals)
- [ ] `context-health/GridTabPanel` migrated to `EntityGridTemplate`
- [ ] AuditEventFeed capability-gap section added to ADR (NOT migration)
- [ ] `filterBuilderPrefix` Storybook story committed
- [ ] Codex peer review — AGREE
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- Metrics + log = additive only, no rollback needed
- ESLint rule: temporarily downgrade to warn if it blocks unrelated PR
- context-health migration: revert single PR commit if regression

### 11.4 PR-4a — Filter/Sort/Null/Injection Matrix (Phase 2)

**Acceptance:**
- [ ] Branch `feat/filter-sort-correctness-matrix` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] `FilterTranslator` becomes column-type-aware (type from schema-service)
- [ ] Generated parameterized cases (~50–70) green
- [ ] Property-based tests for SQL injection (jqwik) green
- [ ] Sort: multi-column, NULL ordering, Turkish collation tests green
- [ ] Multi-year UNION ALL test green
- [ ] MSSQL Testcontainers seed committed
- [ ] `@Tag("integration")` Maven profile wired in CI
- [ ] Codex peer review — AGREE
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- Feature flag `report.filter.typeAware.enabled` falls back to current type-blind translator
- Testcontainers profile is opt-in; turning it off in CI does not block other tests

### 11.5 PR-5 — JoinFragmentRegistry + ColumnTypeRegistry (Phase 2)

**Acceptance:**
- [ ] Branch `feat/join-fragment-registry` opened
- [ ] Codex plan-time consultation — AGREE
- [ ] `JoinFragmentRegistry` schema + 5 initial fragments committed
- [ ] `ColumnTypeRegistry` skeleton + initial entries committed
- [ ] Validator hooks: alias collision, FK graph cross-check
- [ ] ADR `docs/adr/0007-column-type-registry.md` written
- [ ] At least one report adopts a fragment (proof-of-use)
- [ ] Codex peer review — AGREE
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- Registry is read-only metadata; no runtime impact unless adoption
- Adopting report can revert to inline JOIN if fragment proves wrong

### 11.6 PR-6 — Migration Wave (Phase 2)

**Acceptance:**
- [ ] Branch `feat/report-migration-wave-tier-1` opened
- [ ] D-2 + D-3 owner answers received
- [ ] HR reports classified: master (mode=none) or tenant-specific (migrated to current/yearly)
- [ ] satis-ozet + stok-durum migrated to `current` mode
- [ ] Tier 1 batch validator-clean
- [ ] Exceptions burn-down: any remaining exceptions have `expiresAt` ≤ 90 days
- [ ] Codex peer review — AGREE
- [ ] Cluster smoke: every migrated report returns rows for at least one company picker selection
- [ ] `CHANGELOG.md` updated
- [ ] Section 12 status table updated

**Rollback Plan:**
- Each report migration is independent commit
- If a single report regresses: revert that JSON file from git
- Validator stays warn-only for that report until fixed

## 12. Status Tracking

This section is updated at every PR open / merge / deploy.

| PR | Status | Branch | Codex iter | Merged at | Deploy digest | Notes |
|---|---|---|---|---|---|---|
| **PR-0a** | NOT STARTED | — | — | — | — | Phase 0 |
| **PR-0b** | NOT STARTED | — | — | — | — | Phase 0 |
| **PR-1** | NOT STARTED | — | — | — | — | Phase 1 |
| **PR-2** | NOT STARTED | — | — | — | — | Phase 1 |
| **PR-3** | NOT STARTED | — | — | — | — | Phase 1 |
| **PR-4a** | NOT STARTED | — | — | — | — | Phase 2 |
| **PR-5** | NOT STARTED | — | — | — | — | Phase 2 |
| **PR-6** | NOT STARTED | — | — | — | — | Phase 2; depends on D-2 + D-3 |

## 13. Decision Log + Learnings

### 13.1 Decisions

| Date | Decision | Made by |
|---|---|---|
| 2026-05-06 | 8-program plan approved as the working baseline | DRAFT v1 |
| 2026-05-06 | PR-1 combines Programs 1 + 2 (no gap between build-time and runtime gates) | Codex iter-12 |
| 2026-05-06 | Action menu API: single `actions: ReportAction[]` with discriminated union | Codex iter-14 |
| 2026-05-06 | `permission: string \| null`, `undefined` is config error | Codex iter-14 |
| 2026-05-06 | Server-side grouping: proper implement preferred over UI guard | Codex iter-15 |
| 2026-05-06 | Filter test matrix ~50–70 generated cases + 10–15 edge cases (not 140 manual) | Codex iter-15 |
| 2026-05-06 | MSSQL Testcontainers required (no H2) | Codex iter-15 |
| 2026-05-06 | `JoinFragmentRegistry` is vetted-id based, NOT dynamic composer | Codex iter-15 |
| **2026-05-06** | **PR-0 promoted: server-side grouping is the user-reported P0, no longer buried inside PR-4b** | **Claude self-review iter-16** |
| **2026-05-06** | **Phased delivery (Phase 0 / 1 / 2): user value before deep hardening** | **Claude self-review iter-16** |
| **2026-05-06** | **Sprint-end Definition of Done with concrete SLA targets** | **Claude self-review iter-16** |
| **2026-05-06** | **Per-PR rollback plan + feature flag** | **Claude self-review iter-16** |

### 13.2 Learnings (append per PR)

| PR | Lesson |
|---|---|
| _(empty — fill in as PRs complete)_ | _(empty)_ |

## 14. References

- Cross-AI HARD RULE: `~/.claude/CLAUDE.md` (2026-05-05)
- Existing infrastructure docs: `~/.claude/projects/<slug>/memory/`
- Report registry: `report-service/src/main/resources/reports/*.json`
- Canonical schema snapshot: `docs/migration/workcube-schema.json`
- Schema service endpoints: `schema-service/src/main/java/com/example/schema/controller/`

## 15. Approval

This plan requires explicit owner approval before PR-0 starts.

- [ ] **Owner approval** — date / signature
- [ ] **D-1 (PR-0 grouping approach)** — RESOLVED: proper implement (Codex + Claude consensus)
- [ ] **D-2 (HR reports)** — answer:
- [ ] **D-3 (satis-ozet + stok-durum)** — answer:
- [ ] **Architect approval** (multi-customer SaaS direction) — deferred (D-4)

Once approved, change this document's `Status` field from `DRAFT v2` to `ACTIVE`, then proceed with PR-0a + PR-0b.
