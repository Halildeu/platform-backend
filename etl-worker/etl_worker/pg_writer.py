"""PostgreSQL :class:`ReportsDbWriter` adapter (PR-3a).

Adım 12 PR-3a (reporting refactor plan §400 — Docker / K8s / real DB
driver slice, backend half). Codex ``019e2a5c`` plan-time AGREE on
Opt-B′: ship the real ``psycopg`` adapter first (CLI must wire it),
Dockerfile + image CI second, K8s manifest update third.

Design contract (matches :mod:`etl_worker.db` Protocol):

* The adapter owns its own connection / transaction lifecycle. The
  runner only ever calls :meth:`PgReportsDbWriter.upsert` and never
  imports ``psycopg`` directly.
* :class:`PgReportsDbWriter` accepts an injectable ``connect_factory``
  so unit tests pass a fake connection / cursor pair instead of
  spinning up Testcontainers. Production callers use the default
  factory which calls :func:`psycopg.connect`.
* Failures translate to the typed boundary in :mod:`etl_worker.db`:

  - ``UndefinedTable`` / ``UndefinedColumn`` / ``InvalidSchemaName``
    → :class:`~etl_worker.db.ReportsDbSchemaError` (terminal,
    ``EX_SOFTWARE`` at the CLI).
  - Any other driver-side ``Error`` (connection drop, deadlock,
    operational failure, malformed driver state) →
    :class:`~etl_worker.db.ReportsDbWriteError` (operational,
    ``EX_TEMPFAIL`` at the CLI).
* The upsert SQL is idempotent on
  ``(snapshot_signature, contract_version)`` so retry-from-scratch
  of the whole job is safe. The reports_db schema is **assumed to
  exist**: PR-3 does not run DBA migrations; the DBA-owned migration
  PR/runbook is separate.

Expected target schema (DBA-owned, **NOT** created by this adapter)::

    CREATE TABLE etl_snapshot_runs (
        snapshot_signature  text        NOT NULL,
        contract_version    text        NOT NULL,
        allowlist_name      text        NOT NULL,
        allowlist_version   text,
        table_count         integer     NOT NULL,
        column_count        integer     NOT NULL,
        attempts            integer     NOT NULL,
        run_id              text        NOT NULL,
        created_at          timestamptz NOT NULL DEFAULT now(),
        updated_at          timestamptz NOT NULL DEFAULT now(),
        PRIMARY KEY (snapshot_signature, contract_version)
    );

Secret hygiene: the adapter never logs or re-emits ``password``. The
driver-level connection object handles authentication; the password
lives only on the :class:`~etl_worker.config.ReportsDbConfig` field
read once at construction.
"""

from __future__ import annotations

import importlib
import logging
import re
from collections.abc import Callable
from contextlib import AbstractContextManager
from typing import Any, Protocol

from .db import ReportsDbSchemaError, ReportsDbWriteError, ReportsDbWriteResult

_LOG = logging.getLogger(__name__)

UPSERT_SQL = (
    "INSERT INTO etl_snapshot_runs ("
    "snapshot_signature, contract_version, allowlist_name, allowlist_version, "
    "table_count, column_count, attempts, run_id, created_at, updated_at"
    ") VALUES (%s, %s, %s, %s, %s, %s, %s, %s, now(), now()) "
    "ON CONFLICT (snapshot_signature, contract_version) DO UPDATE SET "
    "allowlist_name = EXCLUDED.allowlist_name, "
    "allowlist_version = EXCLUDED.allowlist_version, "
    "table_count = EXCLUDED.table_count, "
    "column_count = EXCLUDED.column_count, "
    "attempts = EXCLUDED.attempts, "
    "run_id = EXCLUDED.run_id, "
    "updated_at = now()"
)
"""Idempotent UPSERT on ``(snapshot_signature, contract_version)``.

Codex ``019e2a5c`` plan-time AGREE: idempotency belongs in the SQL
(``ON CONFLICT`` clause), so retry-from-scratch is safe. Adapter
returns ``rows_written = 1`` on either insert or update path; the
runner does not need to distinguish.
"""

REQUIRED_SUMMARY_FIELDS: tuple[str, ...] = (
    "snapshot_signature",
    "contract_version",
    "allowlist_name",
    "table_count",
    "column_count",
    "attempts",
    "run_id",
)
"""Summary keys the adapter binds to placeholders.

``allowlist_version`` is intentionally optional (None becomes SQL
``NULL``) because the contract allows allowlists without an explicit
version.
"""


class _Connection(Protocol):
    """Subset of the psycopg :class:`Connection` API the adapter uses.

    Defined as a Protocol so unit tests pass a hand-rolled fake without
    importing psycopg at all. The two methods we actually call are
    ``cursor()`` (context manager yielding a cursor) and the
    transaction management dunder methods via the connection's own
    context-manager semantics (commit on exit, rollback on exception).
    """

    def cursor(self) -> AbstractContextManager[Any]:  # pragma: no cover - protocol
        ...

    def commit(self) -> None:  # pragma: no cover - protocol
        ...

    def rollback(self) -> None:  # pragma: no cover - protocol
        ...

    def close(self) -> None:  # pragma: no cover - protocol
        ...


class _ConnectFactory(Protocol):
    """Connection-construction contract.

    Production: :func:`_default_connect_factory` calls
    :func:`psycopg.connect`. Tests: pass a callable returning a fake
    :class:`_Connection` so no driver / network is exercised.
    """

    def __call__(
        self,
        *,
        host: str,
        port: int,
        dbname: str,
        user: str,
        password: str,
        sslmode: str | None,
        connect_timeout: float | None,
    ) -> _Connection:  # pragma: no cover - protocol
        ...


def _default_connect_factory(
    *,
    host: str,
    port: int,
    dbname: str,
    user: str,
    password: str,
    sslmode: str | None,
    connect_timeout: float | None,
) -> _Connection:
    """Default production factory backed by :func:`psycopg.connect`.

    Imports psycopg lazily via :func:`importlib.import_module` so the
    package stays importable on machines without ``libpq`` (dev / lint
    environments using only the fake) AND mypy strict mode does not
    need a ``type: ignore[import-not-found]`` on systems where psycopg
    *is* installed.

    :func:`importlib.import_module` returns ``ModuleType``; attribute
    access on it is ``Any`` by design, so neither the lookup of
    ``psycopg.connect`` nor the return value need a strict-mode
    suppression.

    Failure to import surfaces as :class:`ReportsDbWriteError` at the
    first real call (lazy), not at module import time, so the rest of
    the package stays usable when psycopg is intentionally absent.
    """
    try:
        psycopg = importlib.import_module("psycopg")
    except ImportError as exc:  # pragma: no cover - exercised only when psycopg missing
        raise ReportsDbWriteError(
            "psycopg is not installed — reinstall etl-worker with the "
            "runtime dependency to enable the PostgreSQL adapter"
        ) from exc

    kwargs: dict[str, Any] = {
        "host": host,
        "port": port,
        "dbname": dbname,
        "user": user,
        "password": password,
    }
    if sslmode is not None:
        kwargs["sslmode"] = sslmode
    if connect_timeout is not None:
        kwargs["connect_timeout"] = connect_timeout
    connection: _Connection = psycopg.connect(**kwargs)
    return connection


class PgReportsDbWriter:
    """PostgreSQL implementation of the :class:`ReportsDbWriter` Protocol.

    Each :meth:`upsert` call opens a fresh connection, executes one
    parameterised UPSERT inside one transaction, commits, and closes.
    Connection pooling lives in a later slice if benchmarks justify
    it; the K8s Job model runs a small number of upserts per pod, so
    per-call connect is the simplest correct shape.
    """

    def __init__(
        self,
        *,
        host: str,
        port: int,
        database: str,
        user: str,
        password: str,
        sslmode: str | None = None,
        connect_timeout_seconds: float | None = None,
        connect_factory: _ConnectFactory | None = None,
    ) -> None:
        self._host = host
        self._port = port
        self._database = database
        self._user = user
        self._password = password
        self._sslmode = sslmode
        self._connect_timeout_seconds = connect_timeout_seconds
        self._connect_factory: _ConnectFactory = (
            connect_factory if connect_factory is not None else _default_connect_factory
        )

    def upsert(self, summary: dict[str, object]) -> ReportsDbWriteResult:
        """Idempotently UPSERT one ``etl_snapshot_runs`` row.

        ``summary`` must contain the keys named in
        :data:`REQUIRED_SUMMARY_FIELDS`. ``allowlist_version`` is
        looked up via ``summary.get`` so it cleanly maps to SQL
        ``NULL`` when missing.
        """
        missing = [name for name in REQUIRED_SUMMARY_FIELDS if name not in summary]
        if missing:
            raise ReportsDbWriteError(
                "summary is missing required fields: " + ", ".join(missing)
            )

        params = (
            summary["snapshot_signature"],
            summary["contract_version"],
            summary["allowlist_name"],
            summary.get("allowlist_version"),
            summary["table_count"],
            summary["column_count"],
            summary["attempts"],
            summary["run_id"],
        )

        try:
            connection = self._connect_factory(
                host=self._host,
                port=self._port,
                dbname=self._database,
                user=self._user,
                password=self._password,
                sslmode=self._sslmode,
                connect_timeout=self._connect_timeout_seconds,
            )
        except _SchemaErrorMarker as exc:
            raise ReportsDbSchemaError(
                _safe_message(exc, password=self._password)
            ) from exc
        except Exception as exc:
            if _is_schema_error(exc):
                raise ReportsDbSchemaError(
                    _safe_message(exc, password=self._password)
                ) from exc
            raise ReportsDbWriteError(
                _safe_message(exc, password=self._password)
            ) from exc

        try:
            try:
                with connection.cursor() as cursor:
                    cursor.execute(UPSERT_SQL, params)
                connection.commit()
            except Exception as exc:
                _try_rollback(connection)
                if _is_schema_error(exc):
                    raise ReportsDbSchemaError(
                        _safe_message(exc, password=self._password)
                    ) from exc
                raise ReportsDbWriteError(
                    _safe_message(exc, password=self._password)
                ) from exc
        finally:
            _try_close(connection)

        return ReportsDbWriteResult(rows_written=1)


# ----------------------------------------------------------------------
# Driver-error classification helpers (psycopg-agnostic).
# ----------------------------------------------------------------------


class _SchemaErrorMarker(Exception):
    """Sentinel test helpers can raise to force the schema-error path."""


_SCHEMA_ERROR_CLASS_NAMES: frozenset[str] = frozenset(
    {
        "UndefinedTable",
        "UndefinedColumn",
        "InvalidSchemaName",
    }
)
"""psycopg exception class names that map to :class:`ReportsDbSchemaError`.

Compared by ``type(exc).__name__`` so the adapter does not need a
hard import of ``psycopg.errors``. This keeps the unit tests free of
the driver dependency while still catching the real production class
hierarchy (those classes inherit from ``psycopg.errors.ProgrammingError``).
"""


_SCHEMA_ERROR_SQLSTATE_PREFIXES: tuple[str, ...] = (
    "42P01",  # undefined_table
    "42703",  # undefined_column
    "3F000",  # invalid_schema_name
)


def _is_schema_error(exc: BaseException) -> bool:
    """Return ``True`` when ``exc`` looks like a target-schema mismatch.

    Two signals, either of which is sufficient:

    1. The class name matches one of
       :data:`_SCHEMA_ERROR_CLASS_NAMES` — covers the canonical
       ``psycopg.errors`` exception hierarchy without importing it.
    2. The exception exposes a SQLSTATE prefix in
       :data:`_SCHEMA_ERROR_SQLSTATE_PREFIXES` — covers wrappers that
       re-raise driver errors as a different class but preserve the
       ``sqlstate`` attribute.
    """
    if type(exc).__name__ in _SCHEMA_ERROR_CLASS_NAMES:
        return True
    sqlstate = getattr(exc, "sqlstate", None)
    if isinstance(sqlstate, str) and sqlstate in _SCHEMA_ERROR_SQLSTATE_PREFIXES:
        return True
    return False


# Codex 019e2a5c PR-3a REVISE absorb (blocker #2): cover every common
# shape a driver might use to embed the password in an exception
# message. Order matters: scrub the explicit ``password=`` keyword
# variants first so the longer pattern is consumed before the bare
# raw substring replacement runs.
_PASSWORD_KEYWORD_PATTERNS: tuple[re.Pattern[str], ...] = (
    # ``password=<value>`` / ``PASSWORD=<value>`` — unquoted, whitespace
    # or end-of-string terminated. ``\S`` is greedy enough to swallow
    # adjacent ``port=`` style tokens but the negative-lookahead would
    # bloat the regex; the bare-substring scrub catches that case.
    re.compile(r"(?i)\bpassword\s*=\s*(?P<value>[^\s,;'\"}]+)"),
    # Mapping / dict-repr forms: ``'password': 'secret'`` /
    # ``"password":"secret"`` / ``password: secret``.
    re.compile(r"""(?i)['"]?\bpassword['"]?\s*:\s*['"]?(?P<value>[^'",}\s]+)['"]?"""),
)
_PASSWORD_PLACEHOLDER = "***"


def _safe_message(exc: BaseException, *, password: str | None = None) -> str:
    """Return a stderr-safe message that never includes the password.

    psycopg surfaces connect errors with the full kwargs dict embedded
    in ``str(exc)``. The adapter cannot trust that the driver scrubs
    secrets, so this function is responsible for three independent
    scrub passes:

    1. **Bare raw password** — if the literal password string appears
       anywhere in the message, replace every occurrence with
       :data:`_PASSWORD_PLACEHOLDER`. Catches accidental ``str(kwargs)``
       dumps that don't even use the ``password=`` keyword.
    2. **Keyword form** — case-insensitive ``password=<value>`` and
       ``password = <value>`` variants get their value replaced with
       :data:`_PASSWORD_PLACEHOLDER`. Survives extra whitespace.
    3. **Mapping form** — ``'password': '<value>'`` / ``"password":
       "<value>"`` / ``password: <value>`` (dict-repr / YAML-ish).

    Empty exception messages fall back to the exception class name so
    the CLI never prints a bare colon.
    """
    message = str(exc)
    if password:
        message = message.replace(password, _PASSWORD_PLACEHOLDER)
    for pattern in _PASSWORD_KEYWORD_PATTERNS:
        message = pattern.sub(_replace_value_with_placeholder, message)
    return message or type(exc).__name__


def _replace_value_with_placeholder(match: re.Match[str]) -> str:
    """Substitute the captured ``value`` group in a regex match with the placeholder."""
    return match.group(0).replace(match.group("value"), _PASSWORD_PLACEHOLDER)


def _try_rollback(connection: _Connection) -> None:
    """Best-effort rollback that never raises into the caller path."""
    try:
        connection.rollback()
    except Exception:
        _LOG.debug("rollback failed", exc_info=True)


def _try_close(connection: _Connection) -> None:
    """Best-effort close so a leaked connection never crashes the runner."""
    try:
        connection.close()
    except Exception:
        _LOG.debug("close failed", exc_info=True)


__all__ = [
    "REQUIRED_SUMMARY_FIELDS",
    "UPSERT_SQL",
    "PgReportsDbWriter",
    "_ConnectFactory",  # re-exported for tests
    "_Connection",  # re-exported for tests
]


# Static type-narrow re-export so callers reaching into the module
# see the public Protocol identifiers without ``noqa`` discipline.
def _build_factory(callback: Callable[..., _Connection]) -> _ConnectFactory:
    """Test/dev helper to wrap an arbitrary callable as a typed factory."""
    return callback
