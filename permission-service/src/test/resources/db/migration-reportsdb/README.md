# Test-only mirror of platform-k8s-gitops `sql/migration/`

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
| `V90__test_workcube_fixtures.sql` | (NOT mirrored — test-only) | workcube_mikrolink.* fixture rows referenced by the integration test |

## Drift risk

The four mirrored files can drift from gitops between releases. Mitigations:

1. **CI gate**: gitops repo runs the same Hibernate validate / V19 trigger
   assertion against an isolated Postgres container. A schema change there
   that breaks permission-service's expectations would fail the gitops CI
   before it is merged.
2. **Mechanical mirror is short-lived**: this mirror is a stop-gap until a
   future "schema artifact publish" pipeline (TODO: tracked in the platform
   roadmap) lets gitops emit a versioned SQL artifact that backend services
   pull from a single source.
3. **Manual sync protocol**: when a new V<N> migration lands in gitops and
   it touches data_access.* or any workcube_mikrolink.* table referenced by
   permission-service, the contributor must:
   - copy the file into this directory verbatim;
   - update `permission-service/src/test/resources/db/migration-reportsdb/`
     in the same PR;
   - re-run `./mvnw -pl permission-service test` locally to confirm the
     integration test still passes.

## What is NOT mirrored

- Migrations earlier than V16 (the integration test does not exercise them).
- Application migrations that target the **primary** permission_db
  (`permission-service/src/main/resources/db/migration/V*`); those live in
  the service's own classpath and are managed by the service's normal
  Flyway flow.
