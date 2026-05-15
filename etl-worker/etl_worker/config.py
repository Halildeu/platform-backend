"""Environment-driven configuration for the etl-worker CLI.

Adım 12 PR-2a (reporting refactor plan §400 — config / CLI / client
wiring follow-up to PR-1's ``SchemaServiceClient`` scaffold). This
module is intentionally **library-testable**: nothing here touches
``os.environ`` directly outside :meth:`Config.from_env`, so unit
tests pass an explicit ``env`` mapping and never depend on process
state.

Environment variables consumed:

* ``SCHEMA_SERVICE_URL`` — required. Base URL of the schema-service
  HTTP endpoint (e.g. ``http://schema-service.platform-test:8096``).
  Must parse as ``http://`` / ``https://`` with a non-empty host and
  carry no embedded credentials (those belong in
  ``SCHEMA_SERVICE_INTERNAL_API_KEY``). Trailing slashes are normalised
  off so the resulting URL is canonical.
* ``SCHEMA_SERVICE_INTERNAL_API_KEY`` — optional. Forwarded as the
  ``X-Internal-Api-Key`` header on every request, matching the
  schema-service ``SchemaController`` auth pathway. Blank means the
  passthrough path (dev / test profile) is in use.
* ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` — optional. HTTP request timeout
  in seconds. Default 10. Must parse as a positive ``float``.
* ``SCHEMA_SERVICE_SCHEMA`` — optional. Default value for the
  ``?schema=`` query selector. CLI ``--schema`` overrides; absence
  lets schema-service fall back to its configured default schema.
* ``SCHEMA_SERVICE_CONTRACT_VERSIONS`` — optional. CSV of contract
  versions the worker accepts. Default ``"1"`` matching
  :data:`etl_worker.schema_service_client.SUPPORTED_CONTRACT_VERSION`.
  Whitespace around each entry is trimmed; empty values reject.
* ``SCHEMA_SERVICE_SNAPSHOT_PATH`` — optional. The HTTP path appended
  to ``SCHEMA_SERVICE_URL`` for the snapshot fetch. Default
  :data:`~etl_worker.schema_service_client.DEFAULT_SNAPSHOT_PATH`
  (``/api/v1/schema/reporting-contract`` — the Adım 12 target-contract
  endpoint). Must be an absolute path: it has to start with ``/`` and
  must NOT carry a query string (the ``?schema=`` selector is appended
  separately by the client). Blank falls back to the default.

reports_db (PR-3a) — when the operator opts into ``--reports-db postgres``
the CLI requires a full :class:`ReportsDbConfig`. All five core fields
(``REPORTS_DB_HOST`` / ``REPORTS_DB_PORT`` / ``REPORTS_DB_DATABASE`` /
``REPORTS_DB_USER`` / ``REPORTS_DB_PASSWORD``) must be set together;
partial configuration is fail-closed at :meth:`Config.from_env` time so
the CLI never silently degrades to a no-op DB writer:

* ``REPORTS_DB_HOST`` — PostgreSQL hostname (non-empty).
* ``REPORTS_DB_PORT`` — positive integer (1-65535).
* ``REPORTS_DB_DATABASE`` — PostgreSQL database name (non-empty).
* ``REPORTS_DB_USER`` — PostgreSQL role (non-empty, secret).
* ``REPORTS_DB_PASSWORD`` — secret. Validated as non-empty so an
  unbound ``ExternalSecret`` cannot pass the fail-closed gate.
* ``REPORTS_DB_SSLMODE`` — optional. Forwarded to ``psycopg.connect``
  as the ``sslmode`` keyword (``disable`` / ``require`` / ``verify-ca``
  / ``verify-full``). Absent means driver default.
* ``REPORTS_DB_CONNECT_TIMEOUT_SECONDS`` — optional positive finite
  float, forwarded as ``connect_timeout``. Default unset = driver
  default.

When none of the five core fields are set the resulting
:class:`Config` carries ``reports_db = None`` and the CLI defaults to
the byte-compat no-DB-writer path. When some-but-not-all are set the
config rejects with :class:`ConfigError` so the operator notices the
partial wiring before it ships.

All validation errors raise :class:`ConfigError`; the CLI layer is
responsible for translating those to exit code ``64`` (``EX_USAGE``)
without leaking secret-bearing values to stderr.
"""

from __future__ import annotations

import math
import os
from collections.abc import Mapping
from dataclasses import dataclass
from urllib.parse import urlparse

from .schema_service_client import DEFAULT_SNAPSHOT_PATH, SUPPORTED_CONTRACT_VERSION

DEFAULT_TIMEOUT_SECONDS = 10.0
"""CLI default when ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` is unset."""


class ConfigError(ValueError):
    """Configuration validation failed.

    Library-only error type; the CLI translates this to ``exit 64``.
    """


@dataclass(frozen=True, slots=True)
class ReportsDbConfig:
    """Immutable PostgreSQL reports_db connection profile (PR-3a).

    Codex ``019e2a5c`` PR-3 plan-time AGREE: keep secret and non-secret
    fields explicitly separate (ConfigMap vs Secret in K8s), do not
    accept a single ``REPORTS_DB_URL`` DSN string. The CLI layer
    instantiates :class:`~etl_worker.pg_writer.PgReportsDbWriter` from
    these fields when ``--reports-db postgres`` is selected.
    """

    host: str
    port: int
    database: str
    user: str
    password: str
    sslmode: str | None = None
    connect_timeout_seconds: float | None = None


@dataclass(frozen=True, slots=True)
class Config:
    """Immutable resolved configuration for one CLI invocation."""

    schema_service_url: str
    schema_service_internal_api_key: str | None
    schema_service_timeout_seconds: float
    schema_service_schema: str | None
    schema_service_contract_versions: tuple[str, ...]
    schema_service_snapshot_path: str = DEFAULT_SNAPSHOT_PATH
    reports_db: ReportsDbConfig | None = None

    @classmethod
    def from_env(cls, env: Mapping[str, str] | None = None) -> Config:
        """Resolve a :class:`Config` from a process environment mapping.

        Parameters
        ----------
        env:
            Mapping of environment variables. Defaults to ``os.environ``
            so callers (the CLI) can simply ``Config.from_env()``. Unit
            tests should always pass an explicit dict to keep
            assertions deterministic.

        Raises
        ------
        ConfigError
            Any environment value fails validation. The message is
            safe to surface to stderr; it never echoes the offending
            value if that value might be a secret.
        """
        source = env if env is not None else os.environ

        raw_url = source.get("SCHEMA_SERVICE_URL", "").strip()
        if not raw_url:
            raise ConfigError("SCHEMA_SERVICE_URL is required")
        url = _validate_url(raw_url)

        raw_key = source.get("SCHEMA_SERVICE_INTERNAL_API_KEY")
        if raw_key is not None:
            raw_key = raw_key.strip()
        internal_api_key = raw_key if raw_key else None

        timeout = _parse_timeout(source.get("SCHEMA_SERVICE_TIMEOUT_SECONDS"))

        raw_schema = source.get("SCHEMA_SERVICE_SCHEMA")
        if raw_schema is not None:
            raw_schema = raw_schema.strip()
        schema = raw_schema if raw_schema else None

        versions = _parse_contract_versions(source.get("SCHEMA_SERVICE_CONTRACT_VERSIONS"))

        snapshot_path = _parse_snapshot_path(source.get("SCHEMA_SERVICE_SNAPSHOT_PATH"))

        reports_db = _parse_reports_db(source)

        return cls(
            schema_service_url=url,
            schema_service_internal_api_key=internal_api_key,
            schema_service_timeout_seconds=timeout,
            schema_service_schema=schema,
            schema_service_contract_versions=versions,
            schema_service_snapshot_path=snapshot_path,
            reports_db=reports_db,
        )


def _validate_url(raw: str) -> str:
    """Validate + canonicalise ``SCHEMA_SERVICE_URL``.

    Accepts ``http://`` / ``https://`` schemes only, rejects embedded
    credentials (those belong in ``SCHEMA_SERVICE_INTERNAL_API_KEY``),
    requires a non-empty host, validates the port range, and strips a
    trailing slash so the resulting URL composes cleanly with
    ``snapshot_path``.

    Codex 019e2a5c REVISE absorb: malformed URLs that
    :func:`urllib.parse.urlparse` rejects with ``ValueError`` (e.g.
    unbracketed-IPv6 ``http://[::1`` or non-numeric port
    ``http://host:badport``) must surface as :class:`ConfigError`
    rather than leak the raw stdlib exception — otherwise the CLI
    `EX_USAGE=64` contract is broken.
    """
    try:
        parsed = urlparse(raw)
    except ValueError as exc:
        raise ConfigError("SCHEMA_SERVICE_URL is not a valid URL") from exc
    if parsed.scheme not in {"http", "https"}:
        raise ConfigError(
            "SCHEMA_SERVICE_URL must be an http:// or https:// URL"
        )
    # Touching ``hostname`` and ``port`` after a successful ``urlparse``
    # can still raise ``ValueError`` for some malformed inputs (e.g.
    # ``http://host:badport`` only validates the port lazily). Trap
    # both here so the CLI keeps its typed-error contract.
    try:
        hostname = parsed.hostname
        _ = parsed.port  # property-side validation of numeric / range
    except ValueError as exc:
        raise ConfigError(
            "SCHEMA_SERVICE_URL has an invalid host or port"
        ) from exc
    if not hostname:
        raise ConfigError("SCHEMA_SERVICE_URL is missing a host")
    if parsed.username or parsed.password:
        raise ConfigError(
            "SCHEMA_SERVICE_URL must not embed credentials — use "
            "SCHEMA_SERVICE_INTERNAL_API_KEY instead"
        )
    return raw.rstrip("/")


def _parse_timeout(raw: str | None) -> float:
    """Parse ``SCHEMA_SERVICE_TIMEOUT_SECONDS`` (positive finite float).

    ``nan`` and ``inf`` are rejected via :func:`math.isfinite`. Plain
    comparisons (``value <= 0``) are false for NaN, so without the
    finite check a ``nan`` env value would silently pass validation
    and then break the retry / timeout contract downstream.
    """
    if raw is None or raw.strip() == "":
        return DEFAULT_TIMEOUT_SECONDS
    try:
        value = float(raw)
    except (TypeError, ValueError) as exc:
        raise ConfigError(
            "SCHEMA_SERVICE_TIMEOUT_SECONDS must be a positive finite number"
        ) from exc
    if not math.isfinite(value) or value <= 0:
        raise ConfigError(
            "SCHEMA_SERVICE_TIMEOUT_SECONDS must be a positive finite number"
        )
    return value


_REPORTS_DB_CORE_FIELDS: tuple[str, ...] = (
    "REPORTS_DB_HOST",
    "REPORTS_DB_PORT",
    "REPORTS_DB_DATABASE",
    "REPORTS_DB_USER",
    "REPORTS_DB_PASSWORD",
)
"""Five envs that together define a reports_db profile.

If *all five* are unset, the runner stays on the byte-compat
no-DB-writer path. If *any* are set, *all five* must be set —
otherwise :func:`_parse_reports_db` raises :class:`ConfigError` so
the CLI fails closed instead of silently degrading to no-op DB writes.
"""


def _parse_reports_db(source: Mapping[str, str]) -> ReportsDbConfig | None:
    """Parse the five core ``REPORTS_DB_*`` envs plus two optional knobs.

    Codex ``019e2a5c`` PR-3 plan-time AGREE: partial wiring is a
    fail-closed condition. Either zero of the five core fields are set
    (returns ``None``) or all five are set and validate (returns a
    fully populated :class:`ReportsDbConfig`); anything in between
    raises :class:`ConfigError`.

    ``sslmode`` and ``connect_timeout_seconds`` are independently
    optional: they only validate when the core profile is non-empty,
    so an operator can choose ``REPORTS_DB_SSLMODE`` without forcing a
    connect-timeout override.
    """
    presence = {name: source.get(name, "").strip() for name in _REPORTS_DB_CORE_FIELDS}
    set_fields = [name for name, value in presence.items() if value]
    if not set_fields:
        return None
    if len(set_fields) != len(_REPORTS_DB_CORE_FIELDS):
        missing = sorted(set(_REPORTS_DB_CORE_FIELDS) - set(set_fields))
        raise ConfigError(
            "reports_db configuration is partial — missing "
            + ", ".join(missing)
            + " (all of "
            + ", ".join(_REPORTS_DB_CORE_FIELDS)
            + " must be set together)"
        )

    host = presence["REPORTS_DB_HOST"]
    port = _parse_port(presence["REPORTS_DB_PORT"])
    database = presence["REPORTS_DB_DATABASE"]
    user = presence["REPORTS_DB_USER"]
    password = presence["REPORTS_DB_PASSWORD"]

    raw_sslmode = source.get("REPORTS_DB_SSLMODE")
    sslmode: str | None
    if raw_sslmode is None:
        sslmode = None
    else:
        stripped = raw_sslmode.strip()
        if stripped == "":
            sslmode = None
        elif stripped not in {"disable", "allow", "prefer", "require", "verify-ca", "verify-full"}:
            raise ConfigError(
                "REPORTS_DB_SSLMODE must be one of disable / allow / prefer / "
                "require / verify-ca / verify-full"
            )
        else:
            sslmode = stripped

    connect_timeout = _parse_optional_connect_timeout(
        source.get("REPORTS_DB_CONNECT_TIMEOUT_SECONDS")
    )

    return ReportsDbConfig(
        host=host,
        port=port,
        database=database,
        user=user,
        password=password,
        sslmode=sslmode,
        connect_timeout_seconds=connect_timeout,
    )


def _parse_port(raw: str) -> int:
    """Parse ``REPORTS_DB_PORT`` (positive integer in TCP range)."""
    try:
        value = int(raw)
    except (TypeError, ValueError) as exc:
        raise ConfigError(
            "REPORTS_DB_PORT must be an integer between 1 and 65535"
        ) from exc
    if value < 1 or value > 65535:
        raise ConfigError(
            "REPORTS_DB_PORT must be an integer between 1 and 65535"
        )
    return value


def _parse_optional_connect_timeout(raw: str | None) -> float | None:
    """Parse ``REPORTS_DB_CONNECT_TIMEOUT_SECONDS`` (positive finite float)."""
    if raw is None:
        return None
    stripped = raw.strip()
    if stripped == "":
        return None
    try:
        value = float(stripped)
    except (TypeError, ValueError) as exc:
        raise ConfigError(
            "REPORTS_DB_CONNECT_TIMEOUT_SECONDS must be a positive finite number"
        ) from exc
    if not math.isfinite(value) or value <= 0:
        raise ConfigError(
            "REPORTS_DB_CONNECT_TIMEOUT_SECONDS must be a positive finite number"
        )
    return value


def _parse_snapshot_path(raw: str | None) -> str:
    """Parse ``SCHEMA_SERVICE_SNAPSHOT_PATH`` (absolute path, no query).

    Codex ``019e2d64`` PR-4a plan-time AGREE: the path must be an
    absolute path (starts with ``/``) and must NOT carry a query
    string — the ``?schema=`` selector is appended separately by
    :meth:`~etl_worker.schema_service_client.SchemaServiceClient.fetch_snapshot`.
    A query string baked into the path would produce a malformed
    ``...?schema=...?schema=...`` URL.

    ``None`` (unset) and an empty / whitespace string fall back to
    :data:`~etl_worker.schema_service_client.DEFAULT_SNAPSHOT_PATH`.
    """
    if raw is None:
        return DEFAULT_SNAPSHOT_PATH
    stripped = raw.strip()
    if stripped == "":
        return DEFAULT_SNAPSHOT_PATH
    if not stripped.startswith("/"):
        raise ConfigError(
            "SCHEMA_SERVICE_SNAPSHOT_PATH must be an absolute path "
            "starting with '/'"
        )
    if "?" in stripped or "#" in stripped:
        raise ConfigError(
            "SCHEMA_SERVICE_SNAPSHOT_PATH must not contain a query "
            "string or fragment — the ?schema= selector is appended "
            "by the client"
        )
    return stripped


def _parse_contract_versions(raw: str | None) -> tuple[str, ...]:
    """Parse ``SCHEMA_SERVICE_CONTRACT_VERSIONS`` CSV.

    ``None`` (unset) and an empty string fall back to
    :data:`~etl_worker.schema_service_client.SUPPORTED_CONTRACT_VERSION`.
    Explicit non-empty input is split on commas, whitespace-trimmed;
    any empty piece rejects (so ``"1,"`` and ``" , "`` both raise).
    """
    if raw is None:
        return (SUPPORTED_CONTRACT_VERSION,)
    stripped = raw.strip()
    if stripped == "":
        return (SUPPORTED_CONTRACT_VERSION,)
    pieces = [piece.strip() for piece in stripped.split(",")]
    if any(piece == "" for piece in pieces):
        raise ConfigError(
            "SCHEMA_SERVICE_CONTRACT_VERSIONS must be a non-empty CSV of "
            "version strings (no empty entries)"
        )
    return tuple(pieces)
