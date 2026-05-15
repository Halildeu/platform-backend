"""Unit tests for :class:`etl_worker.config.Config`.

Every test injects an explicit ``env`` mapping so ``os.environ`` is
never touched. Adım 12 PR-2a scope.
"""

from __future__ import annotations

import pytest

from etl_worker.config import (
    DEFAULT_TIMEOUT_SECONDS,
    Config,
    ConfigError,
    ReportsDbConfig,
)


def _full_reports_db_env() -> dict[str, str]:
    """All five core REPORTS_DB_* envs set to plausible test values."""
    return {
        "REPORTS_DB_HOST": "postgres.platform-test",
        "REPORTS_DB_PORT": "5432",
        "REPORTS_DB_DATABASE": "reports_db",
        "REPORTS_DB_USER": "etl_writer",
        "REPORTS_DB_PASSWORD": "s3cret",
    }


def _env(**overrides: str) -> dict[str, str]:
    """Build a minimal valid env, mutated by ``overrides``."""
    base: dict[str, str] = {"SCHEMA_SERVICE_URL": "http://schema-service.test:8096"}
    base.update(overrides)
    return base


# ---- happy path -----------------------------------------------------------


def test_from_env_only_required_url_uses_defaults() -> None:
    config = Config.from_env(_env())

    assert config.schema_service_url == "http://schema-service.test:8096"
    assert config.schema_service_internal_api_key is None
    assert config.schema_service_timeout_seconds == DEFAULT_TIMEOUT_SECONDS
    assert config.schema_service_schema is None
    assert config.schema_service_contract_versions == ("1",)


def test_from_env_strips_trailing_slash_from_url() -> None:
    config = Config.from_env(_env(SCHEMA_SERVICE_URL="https://schema-service.prod/"))

    assert config.schema_service_url == "https://schema-service.prod"


def test_from_env_propagates_all_optional_values() -> None:
    config = Config.from_env(
        _env(
            SCHEMA_SERVICE_INTERNAL_API_KEY="route-test-key",
            SCHEMA_SERVICE_TIMEOUT_SECONDS="15.5",
            SCHEMA_SERVICE_SCHEMA="workcube_mikrolink_2025",
            SCHEMA_SERVICE_CONTRACT_VERSIONS="1,2,3",
        )
    )

    assert config.schema_service_internal_api_key == "route-test-key"
    assert config.schema_service_timeout_seconds == 15.5
    assert config.schema_service_schema == "workcube_mikrolink_2025"
    assert config.schema_service_contract_versions == ("1", "2", "3")


def test_from_env_csv_with_whitespace_around_versions() -> None:
    config = Config.from_env(_env(SCHEMA_SERVICE_CONTRACT_VERSIONS="1, 2 , 3"))

    assert config.schema_service_contract_versions == ("1", "2", "3")


def test_from_env_blank_internal_key_becomes_none() -> None:
    """Empty / whitespace key is treated as 'no key', not as an empty header."""
    config = Config.from_env(_env(SCHEMA_SERVICE_INTERNAL_API_KEY="   "))

    assert config.schema_service_internal_api_key is None


def test_from_env_blank_optional_strings_become_none() -> None:
    config = Config.from_env(
        _env(
            SCHEMA_SERVICE_SCHEMA="   ",
            SCHEMA_SERVICE_TIMEOUT_SECONDS="",
            SCHEMA_SERVICE_CONTRACT_VERSIONS="",
        )
    )

    assert config.schema_service_schema is None
    # Blank timeout falls back to default
    assert config.schema_service_timeout_seconds == DEFAULT_TIMEOUT_SECONDS
    # Blank CSV falls back to default supported set
    assert config.schema_service_contract_versions == ("1",)


# ---- required-field validation -------------------------------------------


def test_from_env_missing_url_raises() -> None:
    with pytest.raises(ConfigError, match="SCHEMA_SERVICE_URL is required"):
        Config.from_env({})


def test_from_env_blank_url_raises() -> None:
    with pytest.raises(ConfigError, match="SCHEMA_SERVICE_URL is required"):
        Config.from_env({"SCHEMA_SERVICE_URL": "   "})


# ---- URL validation -------------------------------------------------------


@pytest.mark.parametrize(
    "bad_url",
    [
        "schema-service:8096",  # no scheme
        "ftp://schema-service",  # wrong scheme
        "file:///etc/hosts",  # wrong scheme
        "http:///just-a-path",  # no host
    ],
)
def test_from_env_rejects_invalid_url(bad_url: str) -> None:
    with pytest.raises(ConfigError):
        Config.from_env({"SCHEMA_SERVICE_URL": bad_url})


def test_from_env_rejects_url_with_credentials() -> None:
    with pytest.raises(ConfigError, match="must not embed credentials"):
        Config.from_env(
            {"SCHEMA_SERVICE_URL": "http://user:pass@schema-service:8096"}
        )


@pytest.mark.parametrize(
    "malformed_url",
    [
        # Unbracketed IPv6 — urlparse() raises ValueError
        "http://[::1",
        # Non-numeric port — parsed.port raises ValueError on access
        "http://schema-service:badport",
        # Out-of-range port (>65535) — parsed.port raises ValueError on access
        "http://schema-service:99999",
    ],
)
def test_from_env_traps_malformed_url_parse_errors(malformed_url: str) -> None:
    """Codex 019e2a5c REVISE absorb: ``urlparse`` / ``.port`` ``ValueError``s
    must surface as :class:`ConfigError`, not leak raw stdlib exceptions
    that would break the CLI's ``EX_USAGE=64`` contract.
    """
    with pytest.raises(ConfigError):
        Config.from_env({"SCHEMA_SERVICE_URL": malformed_url})


# ---- timeout validation ---------------------------------------------------


@pytest.mark.parametrize(
    "bad_timeout",
    ["abc", "0", "-1", "-5.5"],
)
def test_from_env_rejects_invalid_timeout(bad_timeout: str) -> None:
    with pytest.raises(ConfigError, match="must be a positive finite number"):
        Config.from_env(_env(SCHEMA_SERVICE_TIMEOUT_SECONDS=bad_timeout))


@pytest.mark.parametrize(
    "non_finite_timeout",
    ["nan", "NaN", "inf", "Infinity", "-inf"],
)
def test_from_env_rejects_non_finite_timeout(non_finite_timeout: str) -> None:
    """Codex 019e2a5c REVISE absorb: ``nan`` / ``inf`` slip past ``value <= 0``
    comparisons because NaN comparisons are always false. ``math.isfinite``
    closes the gap so retry/timeout config cannot be silently corrupted."""
    with pytest.raises(ConfigError, match="must be a positive finite number"):
        Config.from_env(_env(SCHEMA_SERVICE_TIMEOUT_SECONDS=non_finite_timeout))


# ---- contract version validation ------------------------------------------


@pytest.mark.parametrize(
    "bad_versions",
    ["1,", ",1", "1,,2", " , ", ","],
)
def test_from_env_rejects_empty_csv_entries(bad_versions: str) -> None:
    with pytest.raises(ConfigError, match="non-empty CSV"):
        Config.from_env(_env(SCHEMA_SERVICE_CONTRACT_VERSIONS=bad_versions))


def test_from_env_falls_back_to_os_environ_when_env_none(monkeypatch: pytest.MonkeyPatch) -> None:
    """Passing ``env=None`` uses ``os.environ`` — sanity check for the production
    code path where the CLI calls ``Config.from_env()``."""
    monkeypatch.setenv("SCHEMA_SERVICE_URL", "https://from-os-environ:9000")
    monkeypatch.delenv("SCHEMA_SERVICE_TIMEOUT_SECONDS", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_INTERNAL_API_KEY", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_SCHEMA", raising=False)
    monkeypatch.delenv("SCHEMA_SERVICE_CONTRACT_VERSIONS", raising=False)

    config = Config.from_env()

    assert config.schema_service_url == "https://from-os-environ:9000"


# ---- reports_db (PR-3a) ---------------------------------------------------


def test_reports_db_none_when_all_envs_unset() -> None:
    """No REPORTS_DB_* envs at all → ``Config.reports_db is None`` (byte-compat)."""
    config = Config.from_env(_env())
    assert config.reports_db is None


def test_reports_db_full_profile_parses() -> None:
    config = Config.from_env(_env(**_full_reports_db_env()))
    assert config.reports_db == ReportsDbConfig(
        host="postgres.platform-test",
        port=5432,
        database="reports_db",
        user="etl_writer",
        password="s3cret",
        sslmode=None,
        connect_timeout_seconds=None,
    )


def test_reports_db_with_optional_sslmode_and_connect_timeout() -> None:
    extras = {
        "REPORTS_DB_SSLMODE": "require",
        "REPORTS_DB_CONNECT_TIMEOUT_SECONDS": "7.5",
    }
    config = Config.from_env(_env(**_full_reports_db_env(), **extras))
    assert config.reports_db is not None
    assert config.reports_db.sslmode == "require"
    assert config.reports_db.connect_timeout_seconds == 7.5


@pytest.mark.parametrize(
    "missing_field",
    [
        "REPORTS_DB_HOST",
        "REPORTS_DB_PORT",
        "REPORTS_DB_DATABASE",
        "REPORTS_DB_USER",
        "REPORTS_DB_PASSWORD",
    ],
)
def test_reports_db_partial_wiring_fails_closed(missing_field: str) -> None:
    profile = _full_reports_db_env()
    profile.pop(missing_field)
    with pytest.raises(ConfigError, match="reports_db configuration is partial"):
        Config.from_env(_env(**profile))


@pytest.mark.parametrize(
    "missing_field",
    [
        "REPORTS_DB_HOST",
        "REPORTS_DB_PORT",
        "REPORTS_DB_DATABASE",
        "REPORTS_DB_USER",
        "REPORTS_DB_PASSWORD",
    ],
)
def test_reports_db_blank_value_counts_as_missing(missing_field: str) -> None:
    profile = _full_reports_db_env()
    profile[missing_field] = "   "
    with pytest.raises(ConfigError, match="reports_db configuration is partial"):
        Config.from_env(_env(**profile))


@pytest.mark.parametrize("bad_port", ["0", "65536", "abc", "-5", "3.14"])
def test_reports_db_port_must_be_in_tcp_range(bad_port: str) -> None:
    profile = _full_reports_db_env()
    profile["REPORTS_DB_PORT"] = bad_port
    with pytest.raises(ConfigError, match="REPORTS_DB_PORT must be"):
        Config.from_env(_env(**profile))


@pytest.mark.parametrize(
    "good_sslmode",
    ["disable", "allow", "prefer", "require", "verify-ca", "verify-full"],
)
def test_reports_db_accepts_valid_sslmode(good_sslmode: str) -> None:
    profile = _full_reports_db_env()
    config = Config.from_env(_env(**profile, REPORTS_DB_SSLMODE=good_sslmode))
    assert config.reports_db is not None
    assert config.reports_db.sslmode == good_sslmode


def test_reports_db_rejects_unknown_sslmode() -> None:
    profile = _full_reports_db_env()
    with pytest.raises(ConfigError, match="REPORTS_DB_SSLMODE"):
        Config.from_env(_env(**profile, REPORTS_DB_SSLMODE="off"))


@pytest.mark.parametrize("bad_timeout", ["0", "-1", "nan", "inf", "abc"])
def test_reports_db_rejects_non_positive_or_non_finite_connect_timeout(
    bad_timeout: str,
) -> None:
    profile = _full_reports_db_env()
    with pytest.raises(ConfigError, match="REPORTS_DB_CONNECT_TIMEOUT_SECONDS"):
        Config.from_env(
            _env(**profile, REPORTS_DB_CONNECT_TIMEOUT_SECONDS=bad_timeout)
        )


def test_reports_db_blank_sslmode_treated_as_unset() -> None:
    profile = _full_reports_db_env()
    config = Config.from_env(_env(**profile, REPORTS_DB_SSLMODE="   "))
    assert config.reports_db is not None
    assert config.reports_db.sslmode is None


def test_reports_db_blank_connect_timeout_treated_as_unset() -> None:
    profile = _full_reports_db_env()
    config = Config.from_env(
        _env(**profile, REPORTS_DB_CONNECT_TIMEOUT_SECONDS=" ")
    )
    assert config.reports_db is not None
    assert config.reports_db.connect_timeout_seconds is None


# ---- snapshot path (PR-4a) ------------------------------------------------


def test_snapshot_path_defaults_to_reporting_contract() -> None:
    """Unset SCHEMA_SERVICE_SNAPSHOT_PATH → the Adım 12 target endpoint."""
    config = Config.from_env(_env())
    assert config.schema_service_snapshot_path == "/api/v1/schema/reporting-contract"


def test_snapshot_path_blank_falls_back_to_default() -> None:
    config = Config.from_env(_env(SCHEMA_SERVICE_SNAPSHOT_PATH="   "))
    assert config.schema_service_snapshot_path == "/api/v1/schema/reporting-contract"


def test_snapshot_path_explicit_override() -> None:
    config = Config.from_env(
        _env(SCHEMA_SERVICE_SNAPSHOT_PATH="/api/v1/schema/snapshot")
    )
    assert config.schema_service_snapshot_path == "/api/v1/schema/snapshot"


@pytest.mark.parametrize(
    "bad_path",
    ["api/v1/schema/x", "schema/snapshot", "relative/path"],
)
def test_snapshot_path_must_be_absolute(bad_path: str) -> None:
    with pytest.raises(ConfigError, match="absolute path"):
        Config.from_env(_env(SCHEMA_SERVICE_SNAPSHOT_PATH=bad_path))


@pytest.mark.parametrize(
    "bad_path",
    [
        "/api/v1/schema/snapshot?schema=foo",
        "/api/v1/schema/snapshot#frag",
        "/path?x=1",
    ],
)
def test_snapshot_path_rejects_query_or_fragment(bad_path: str) -> None:
    """The ?schema= selector is appended by the client — a query string
    baked into the path would double up."""
    with pytest.raises(ConfigError, match="query string or fragment"):
        Config.from_env(_env(SCHEMA_SERVICE_SNAPSHOT_PATH=bad_path))
