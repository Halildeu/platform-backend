"""Unit tests for :class:`etl_worker.pg_writer.PgReportsDbWriter`.

Adım 12 PR-3a — Codex ``019e2a5c`` plan-time AGREE on hermetic test
shape: no Testcontainers, no psycopg import, hand-rolled fake
connection + cursor pair exercise the adapter's typed boundary.

Coverage:

* parameter binding + SQL contract (golden assertions on the call
  shape, not on full SQL text)
* idempotent ``rows_written = 1`` return
* commit on success, rollback on failure
* connection always closed, even on exception
* ``UndefinedTable`` / ``UndefinedColumn`` / ``InvalidSchemaName``
  (by class name) → :class:`ReportsDbSchemaError`
* SQLSTATE-only path (``42P01`` / ``42703`` / ``3F000``) →
  :class:`ReportsDbSchemaError`
* Any other driver-level exception → :class:`ReportsDbWriteError`
* Connect-time failure → :class:`ReportsDbWriteError` (transient infra)
* Missing required summary field → :class:`ReportsDbWriteError`
* Secret hygiene — exception message never echoes the password
"""

from __future__ import annotations

from typing import Any

import pytest

from etl_worker.db import ReportsDbSchemaError, ReportsDbWriteError
from etl_worker.pg_writer import (
    REQUIRED_SUMMARY_FIELDS,
    UPSERT_SQL,
    PgReportsDbWriter,
)

# ---------------------------------------------------------------------------
# Test doubles — hand-rolled, no psycopg import.
# ---------------------------------------------------------------------------


class _FakeCursor:
    def __init__(self, sql_recorder: list[tuple[str, tuple[object, ...]]]) -> None:
        self._sql_recorder = sql_recorder
        self.exception_to_raise: BaseException | None = None

    def execute(self, sql: str, params: tuple[object, ...]) -> None:
        if self.exception_to_raise is not None:
            raise self.exception_to_raise
        self._sql_recorder.append((sql, params))

    def __enter__(self) -> _FakeCursor:
        return self

    def __exit__(self, *_exc: object) -> None:
        return None


class _FakeConnection:
    def __init__(self) -> None:
        self.executed: list[tuple[str, tuple[object, ...]]] = []
        self.cursor_obj = _FakeCursor(self.executed)
        self.commit_calls = 0
        self.rollback_calls = 0
        self.close_calls = 0
        self.close_raises: BaseException | None = None
        self.rollback_raises: BaseException | None = None

    def cursor(self) -> _FakeCursor:
        return self.cursor_obj

    def commit(self) -> None:
        self.commit_calls += 1

    def rollback(self) -> None:
        self.rollback_calls += 1
        if self.rollback_raises is not None:
            raise self.rollback_raises

    def close(self) -> None:
        self.close_calls += 1
        if self.close_raises is not None:
            raise self.close_raises


class UndefinedTable(Exception):
    """Stand-in for ``psycopg.errors.UndefinedTable`` — matched by class name."""


class UndefinedColumn(Exception):
    pass


class InvalidSchemaName(Exception):
    pass


class _OperationalError(Exception):
    """Stand-in for the operational (retryable) family."""


class _SqlStateError(Exception):
    """Wrapper exception that exposes ``sqlstate`` for the secondary path."""

    def __init__(self, message: str, sqlstate: str) -> None:
        super().__init__(message)
        self.sqlstate = sqlstate


# ---------------------------------------------------------------------------
# Helpers.
# ---------------------------------------------------------------------------


def _summary(**overrides: object) -> dict[str, object]:
    base: dict[str, object] = {
        "snapshot_signature": "deadbeef",
        "contract_version": "1",
        "allowlist_name": "workcube",
        "allowlist_version": "2A-2026-04-25",
        "table_count": 40,
        "column_count": 612,
        "attempts": 1,
        "run_id": "run-xyz",
    }
    base.update(overrides)
    return base


def _build_writer(connection: _FakeConnection, **overrides: object) -> PgReportsDbWriter:
    captured: list[dict[str, Any]] = []

    def factory(
        *,
        host: str,
        port: int,
        dbname: str,
        user: str,
        password: str,
        sslmode: str | None,
        connect_timeout: float | None,
    ) -> _FakeConnection:
        captured.append(
            {
                "host": host,
                "port": port,
                "dbname": dbname,
                "user": user,
                "password": password,
                "sslmode": sslmode,
                "connect_timeout": connect_timeout,
            }
        )
        return connection

    writer = PgReportsDbWriter(
        host=str(overrides.get("host", "pg.test")),
        port=int(overrides.get("port", 5432)),
        database=str(overrides.get("database", "reports_db")),
        user=str(overrides.get("user", "writer")),
        password=str(overrides.get("password", "sup3r-secret-pw")),
        sslmode=overrides.get("sslmode"),  # type: ignore[arg-type]
        connect_timeout_seconds=overrides.get("connect_timeout_seconds"),  # type: ignore[arg-type]
        connect_factory=factory,  # type: ignore[arg-type]
    )
    writer._test_captured_connect_kwargs = captured  # type: ignore[attr-defined]
    return writer


# ---------------------------------------------------------------------------
# Happy path.
# ---------------------------------------------------------------------------


def test_upsert_executes_parameterised_sql_and_commits() -> None:
    connection = _FakeConnection()
    writer = _build_writer(connection)
    summary = _summary()

    result = writer.upsert(summary)

    assert result.rows_written == 1
    assert connection.commit_calls == 1
    assert connection.rollback_calls == 0
    assert connection.close_calls == 1
    # One execute call with the canonical SQL + ordered params.
    assert len(connection.executed) == 1
    sql, params = connection.executed[0]
    assert sql == UPSERT_SQL
    assert params == (
        "deadbeef",
        "1",
        "workcube",
        "2A-2026-04-25",
        40,
        612,
        1,
        "run-xyz",
    )


def test_upsert_handles_missing_optional_allowlist_version() -> None:
    """``allowlist_version`` is optional; ``None`` maps to SQL ``NULL``."""
    connection = _FakeConnection()
    writer = _build_writer(connection)
    summary = _summary()
    summary.pop("allowlist_version")

    result = writer.upsert(summary)

    assert result.rows_written == 1
    _sql, params = connection.executed[0]
    assert params[3] is None  # allowlist_version slot


def test_connect_factory_receives_full_profile() -> None:
    connection = _FakeConnection()
    writer = _build_writer(
        connection,
        host="db.example",
        port=6543,
        database="reports_db_test",
        user="ro_user",
        password="never-leaked",
        sslmode="require",
        connect_timeout_seconds=12.0,
    )

    writer.upsert(_summary())

    captured = writer._test_captured_connect_kwargs  # type: ignore[attr-defined]
    assert captured == [
        {
            "host": "db.example",
            "port": 6543,
            "dbname": "reports_db_test",
            "user": "ro_user",
            "password": "never-leaked",
            "sslmode": "require",
            "connect_timeout": 12.0,
        }
    ]


# ---------------------------------------------------------------------------
# Required-field validation.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("missing_field", REQUIRED_SUMMARY_FIELDS)
def test_missing_required_summary_field_raises_write_error(missing_field: str) -> None:
    connection = _FakeConnection()
    writer = _build_writer(connection)
    summary = _summary()
    summary.pop(missing_field)

    with pytest.raises(ReportsDbWriteError, match="missing required fields"):
        writer.upsert(summary)

    # Pre-validation must run *before* connect — no connection is opened.
    assert connection.commit_calls == 0
    assert connection.executed == []
    assert connection.close_calls == 0


# ---------------------------------------------------------------------------
# Schema-error translation.
# ---------------------------------------------------------------------------


@pytest.mark.parametrize(
    "exc_class",
    [UndefinedTable, UndefinedColumn, InvalidSchemaName],
)
def test_class_name_match_maps_to_schema_error(exc_class: type[Exception]) -> None:
    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = exc_class("schema mismatch")
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbSchemaError, match="schema mismatch"):
        writer.upsert(_summary())

    assert connection.commit_calls == 0
    assert connection.rollback_calls == 1
    assert connection.close_calls == 1


@pytest.mark.parametrize("sqlstate", ["42P01", "42703", "3F000"])
def test_sqlstate_match_maps_to_schema_error(sqlstate: str) -> None:
    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _SqlStateError(
        "wrapped driver failure", sqlstate
    )
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbSchemaError, match="wrapped driver failure"):
        writer.upsert(_summary())


# ---------------------------------------------------------------------------
# Operational-error translation.
# ---------------------------------------------------------------------------


def test_other_driver_error_maps_to_write_error() -> None:
    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _OperationalError("connection reset")
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbWriteError, match="connection reset"):
        writer.upsert(_summary())

    assert connection.rollback_calls == 1
    assert connection.close_calls == 1


def test_unknown_sqlstate_does_not_force_schema_error() -> None:
    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _SqlStateError("deadlock", "40P01")
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbWriteError, match="deadlock"):
        writer.upsert(_summary())


def test_connect_failure_maps_to_write_error() -> None:
    def failing_factory(**_kwargs: object) -> _FakeConnection:
        raise _OperationalError("could not connect to host")

    writer = PgReportsDbWriter(
        host="pg.test",
        port=5432,
        database="reports_db",
        user="writer",
        password="pw",
        connect_factory=failing_factory,  # type: ignore[arg-type]
    )

    with pytest.raises(ReportsDbWriteError, match="could not connect to host"):
        writer.upsert(_summary())


def test_connect_failure_by_class_name_maps_to_schema_error() -> None:
    """A schema-class exception on connect (rare but possible) still maps."""

    def failing_factory(**_kwargs: object) -> _FakeConnection:
        raise InvalidSchemaName("no such schema reports")

    writer = PgReportsDbWriter(
        host="pg.test",
        port=5432,
        database="reports_db",
        user="writer",
        password="pw",
        connect_factory=failing_factory,  # type: ignore[arg-type]
    )

    with pytest.raises(ReportsDbSchemaError, match="no such schema"):
        writer.upsert(_summary())


# ---------------------------------------------------------------------------
# Connection-lifecycle invariants.
# ---------------------------------------------------------------------------


def test_close_failure_does_not_mask_success() -> None:
    connection = _FakeConnection()
    connection.close_raises = _OperationalError("close failed silently")
    writer = _build_writer(connection)

    # ``upsert`` completes successfully; close error is swallowed.
    result = writer.upsert(_summary())

    assert result.rows_written == 1
    assert connection.commit_calls == 1


def test_rollback_failure_does_not_mask_original_error() -> None:
    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _OperationalError("primary fail")
    connection.rollback_raises = _OperationalError("rollback also failed")
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbWriteError, match="primary fail"):
        writer.upsert(_summary())

    # Connection still closed even with rollback failure.
    assert connection.close_calls == 1


# ---------------------------------------------------------------------------
# Secret hygiene.
# ---------------------------------------------------------------------------


class _LeakyError(Exception):
    pass


@pytest.mark.parametrize(
    "leaky_message",
    [
        "connection failed: host=pg.test port=5432 password=sup3r-secret-pw",
        "connection failed: PASSWORD=sup3r-secret-pw retrying",
        "connection failed: password = sup3r-secret-pw retrying",
        "connection failed: {'host': 'pg.test', 'password': 'sup3r-secret-pw'}",
        "connection failed: {\"host\":\"pg.test\",\"password\":\"sup3r-secret-pw\"}",
        # Bare-raw substring path — driver dumped the password value
        # without using the ``password=`` keyword at all (rare but
        # possible with custom Error subclasses).
        "auth failure for sup3r-secret-pw on pg.test",
        # YAML / mapping repr without quotes
        "config: password: sup3r-secret-pw retrying",
    ],
)
def test_password_does_not_leak_into_exception_message(leaky_message: str) -> None:
    """Codex 019e2a5c PR-3a REVISE absorb (blocker #2): every common driver
    error shape that could embed the password must be scrubbed before the
    exception bubbles to the CLI."""

    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _LeakyError(leaky_message)
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbWriteError) as caught:
        writer.upsert(_summary())

    assert "sup3r-secret-pw" not in str(caught.value)
    assert "***" in str(caught.value)


def test_password_scrub_handles_empty_exception_message() -> None:
    """Empty driver message falls back to the exception class name so the
    CLI never prints a bare colon followed by nothing."""

    connection = _FakeConnection()
    connection.cursor_obj.exception_to_raise = _LeakyError("")
    writer = _build_writer(connection)

    with pytest.raises(ReportsDbWriteError) as caught:
        writer.upsert(_summary())

    assert str(caught.value) == "_LeakyError"
