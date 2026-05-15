# etl-worker

Workcube reporting ETL worker — schema-service contract consumer.
Adım 12 of the reporting refactor plan (see
`docs/plan-reporting-refactor-2026-05-14.md` in `platform-k8s-gitops`).

## Status — PR-1 (scaffold)

This is the first slice of Adım 12. PR-1 covers **only** the
schema-service HTTP contract client + typed exceptions + pytest /
ruff / mypy gate. Live ETL work (runner orchestration, MSSQL reads,
reports_db inserts, K8s Job manifest, Docker image) lands in
subsequent PRs.

| Slice | Scope | Status |
|---|---|---|
| PR-1 | `SchemaServiceClient` + contract models + tests + CI gate | this PR |
| PR-2 | Runner / CLI integration + config wiring | pending |
| PR-3 | Dockerfile + K8s Job manifest + test cluster wiring | pending |
| PR-4 | Live smoke against testai schema-service + reports DB writes | pending (operator gate) |

## Provisional location decision

The worker lives at `platform-backend/etl-worker/` for now — top-level
within the platform-backend monorepo. Rationale:

- Top-level rather than nested under `report-service/` because the
  worker is a runtime Job, not a report-service child. It consumes
  schema-service and writes reports_db; report-service runtime is
  separate.
- Monorepo subdirectory rather than a brand-new `Halildeu/etl-worker`
  repo because new GitHub repo creation is a user-level decision under
  CLAUDE.md. If a later ADR moves this out, `git filter-repo` can
  carve out the history cleanly.

## Layout

```
etl-worker/
├── README.md
├── pyproject.toml
├── etl_worker/
│   ├── __init__.py
│   ├── contracts.py             # SchemaSnapshot / TableSpec / ColumnSpec dataclasses
│   └── schema_service_client.py # SchemaServiceClient + typed exceptions
└── tests/
    ├── __init__.py
    └── test_schema_service_client.py
```

## Local checks

```
cd etl-worker
python -m pip install -e '.[dev]'
python -m pytest
python -m ruff check etl_worker tests
python -m mypy etl_worker
```

CI runs the same three gates on every push that touches
`etl-worker/**` via `.github/workflows/etl-worker.yml`.

## Runtime dependencies

PR-1 stays **stdlib-only** at runtime (only `urllib.request`). Dev
dependencies are pinned via `[project.optional-dependencies]` `dev`:
`pytest`, `pytest-cov`, `ruff`, `mypy`. New runtime dependencies are
added only when the runner / live ETL slices land, and each one needs
explicit review.

## What PR-1 does NOT do

- No Dockerfile.
- No K8s manifest.
- No live schema-service HTTP call.
- No runner orchestration / scheduling.
- No reports_db connection or insert.
- No parametric / yearly schema enablement claim.

These all belong to follow-up PRs in the Adım 12 sequence above.

## Cross-AI peer review

Adım 12 PR-1 follows the HARD RULE — Cross-AI Peer Review pattern at
provider level:

- Implementer: Claude (Anthropic)
- Reviewer: Codex (OpenAI) thread `019e2a5c` plan-time AGREE on the
  narrow scope above; post-impl diff review pending on the PR itself.
