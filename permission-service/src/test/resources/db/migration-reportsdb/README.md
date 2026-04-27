# Mirror — schema authority gitops, test-only

This directory contains a **read-only mirror** of the production reports_db
schema migrations that live in
[platform-k8s-gitops:sql/migration](https://github.com/Halildeu/platform-k8s-gitops/tree/main/sql/migration).
The originals are the source of truth; the copies here exist solely so
`DataAccessIntegrationTest` can boot a Postgres Testcontainers instance with
the real schema and assert that `AccessScopeService` interacts with it
correctly.

## Why mirror?

Production reports_db migrations are owned by gitops because that is where
the cluster-side Flyway runner picks them up (see gitops
`data-access-migrations.yml` workflow). Embedding them in the
permission-service Java classpath would create two authorities; the mirror
here is **test-classpath-only** and never reaches a deployed cluster.

## Files

| File | Mirrored from gitops | Purpose |
|---|---|---|
| `V16__reports.sql` | `sql/migration/V16__reports.sql` | reports_db schema (workcube_mikrolink canonical + workcube_mssql_raw + migration_audit) |
| `V17__etl_lineage_columns.sql` | `sql/migration/V17__etl_lineage_columns.sql` | source_table + source_pk + lineage UNIQUE INDEX on V16 canonical tables |
| `V19__data_access.sql` | `sql/migration/V19__data_access.sql` | data_access schema (organization, organization_company, scope) + validate_scope_ref trigger + AÇIK seed |
| `V20__data_access_depot_alter.sql` | `sql/migration/V20__data_access_depot_alter.sql` | depot scope_kind widening to DEPARTMENT (Faz 21.A) |
| `V21__validate_scope_ref_json_parse.sql` | `sql/migration/V21__validate_scope_ref_json_parse.sql` | validate_scope_ref() JSON-array `scope_ref` parsing fix (Codex 019dcfb0 BLOCKER #2). Without this, the happy-path integration test would fail: V19's raw comparison `WHERE source_pk = p_ref` never matches the canonical `'["1001"]'` shape against `source_pk='1001'`. |
| `V22__data_access_scope_outbox.sql` | `sql/migration/V22__data_access_scope_outbox.sql` | `data_access.scope_outbox` transactional outbox table (12 columns) + 5 indexes (claim / scope-ordering / recovery / failed / scope_id) + `recover_stuck_outbox_rows()` plpgsql function. Codex 019dcf5c iter-2 strategic primary recommendation; underpins PR-G's TX-internal outbox INSERT pattern (FGA write deferred to async poller). |
| `V23__outbox_tuple_typed_columns.sql` | `sql/migration/V23__outbox_tuple_typed_columns.sql` | Adds `tuple_user`, `tuple_relation`, `tuple_object` (TEXT NOT NULL) columns to `scope_outbox`, drops V22 `idx_scope_outbox_scope_ordering`, creates `idx_scope_outbox_tuple_ordering` partial index. Codex 019dd0e0 iter-2 BLOCKER 2 absorb (Yol β) — same-tuple ordering across scope_id boundaries (revoke + re-grant produces different scope.id targeting same FGA tuple, so scope_id-based ordering was correctness-incorrect). |
| `V90__test_workcube_fixtures.sql` | (NOT mirrored — test-only) | `workcube_mikrolink.*` fixture rows referenced by the integration test |

## Sync state

- **Source**: `Halildeu/platform-k8s-gitops:main:sql/migration/`
- **Last sync**: 2026-04-28 (PR-G iter-2 absorb, Codex 019dd0e0 BLOCKER 2 fix)
- **Source commits at sync**:
  - V16: `c818dc3` (Faz 16 ETL canonical)
  - V17: `aa6aeb1` (Faz 21.1 manifest enrichment)
  - V19: `aa6aeb1` (Faz 21 data_access seed)
  - V20: `aa6aeb1` (Faz 21.A depot=DEPARTMENT)
  - V21: `924002a` (gitops PR #186 merge — Codex 019dcfb0 BLOCKER #2 fix)
  - V22: `63e0adb` (gitops PR #187 merge — Codex 019dcf5c iter-2 outbox table)
  - V23: `06ed15e` (gitops PR #188 merge — Codex 019dd0e0 BLOCKER 2 tuple typed columns)

## Checksums (verify drift)

| File | SHA-256 |
|---|---|
| `V16__reports.sql` | `c3aa5862139ace0c479ca67339cf45c0ebaacd525d36c128f9a53cb252de5509` |
| `V17__etl_lineage_columns.sql` | `c207a7a8624983014659ca714d80275e4266dda263d1d7f87c73ada4c91808e9` |
| `V19__data_access.sql` | `eb2cbdb3cec2aa3301eb9011e5bddf568f1e87672b335413f440bb27ca4fc456` |
| `V20__data_access_depot_alter.sql` | `f3e09bd6006501670ec446f82c4442b57112d6bffcf42a9a56d63083949c7826` |
| `V21__validate_scope_ref_json_parse.sql` | `c8de875f2c957f2c6d931d2f5af36a82e3df63570e4e3226ad315da1c99ade15` |
| `V22__data_access_scope_outbox.sql` | `68c077ee346ab3f9cd243777603cd6d4d7de1877c4b7447ba178447c5b3ed7f6` |
| `V23__outbox_tuple_typed_columns.sql` | `2e25e1c81a6b08fde3541a06879a1ad9d34b548fbacb06bd711d5485032fc938` |
| `V90__test_workcube_fixtures.sql` (test-only) | `b24294b6a57350c9cef575008b0ca00710f94545195b09534f5b6dde5dd9e79e` |

To verify drift: clone gitops at `main`, run `sha256sum sql/migration/V*.sql`,
and compare against the rows above (V16/V17/V19/V20/V21/V22/V23). Mismatches mean
either (a) gitops moved on without a backend mirror update, or (b) an editor
accidentally touched a mirrored file. Both are bugs; fix by re-running the
manual sync protocol below.

Future TODO: a CI drift-detection job will fetch the gitops blobs and
compare SHA-256 — eliminating the manual checksum step. Tracked in the
platform roadmap.

## Drift risk + mitigations

The mirrored files can drift from gitops between releases. Mitigations:

1. **CI gate (gitops side)**: gitops repo runs the same Hibernate validate /
   V19 trigger assertion against an isolated Postgres container. A schema
   change there that breaks permission-service's expectations would fail
   the gitops CI before it is merged.
2. **CI gate (backend side, this PR)**: `.github/workflows/ci-mvn-check.yml`
   adds a `permission-service-integration-test` job that runs
   `./mvnw -pl permission-service test -Pintegration-tests` on every PR.
   Drift between this mirror and the live `data_access` contract breaks
   here long before it reaches staging.
3. **Mechanical mirror is short-lived**: stop-gap until a future "schema
   artifact publish" pipeline (TODO, platform roadmap) lets gitops emit a
   versioned SQL artifact that backend services pull from a single source.
4. **Manual sync protocol**: when a new `V<N>` migration lands in gitops
   touching `data_access.*` or any `workcube_mikrolink.*` table this
   service references, the contributor must:
   - Copy the file into this directory verbatim:
     ```
     gh api -H "Accept: application/vnd.github.raw" \
       /repos/Halildeu/platform-k8s-gitops/contents/sql/migration/V<N>__*.sql \
       > permission-service/src/test/resources/db/migration-reportsdb/V<N>__*.sql
     ```
   - Recompute `sha256sum permission-service/src/test/resources/db/migration-reportsdb/V*.sql`
     and update **Sync state** + **Checksums** above.
   - Re-run the integration tests locally with the
     `integration-tests` Maven profile activated:
     ```
     ./mvnw -pl permission-service test -Pintegration-tests
     ```
   - Land everything in the same PR — the CI integration job will fail
     fast if the contributor forgets the recompute step.

## What is NOT mirrored

- Migrations earlier than V16 (the integration test does not exercise them).
- Application migrations targeting the **primary** permission_db
  (`permission-service/src/main/resources/db/migration/V*`); those live in
  the service's own classpath and are managed by the service's normal
  Flyway flow.
