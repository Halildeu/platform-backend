"""Unit tests for :mod:`etl_worker.audit`.

Adım 12 PR-2b2a — audit trail foundation. Tests run hermetic against
``tmp_path`` so no actual log files leak between cases.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from etl_worker.audit import (
    MAX_ERROR_MESSAGE_LENGTH,
    SCHEMA_VERSION,
    AuditEvent,
    JsonLinesAuditWriter,
    build_event,
    now_isoformat,
)


def _read_lines(path: Path) -> list[dict[str, object]]:
    """Read a JSON Lines audit file into a list of parsed dicts."""
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines()]


# ---- build_event helper --------------------------------------------------


def test_build_event_fills_schema_version_and_timestamp() -> None:
    event = build_event(run_id="r1", event="run_started")

    assert event.schema_version == SCHEMA_VERSION
    assert event.run_id == "r1"
    assert event.event == "run_started"
    # Timestamp is ISO 8601 with Z suffix.
    assert event.timestamp.endswith("Z")
    assert "T" in event.timestamp


def test_build_event_preserves_optional_fields() -> None:
    event = build_event(
        run_id="r1",
        event="attempt_failed",
        attempt=2,
        outcome="retryable_failure",
        error_class="SchemaServiceUnavailable",
        error_message="503",
        extra={"hint": "upstream busy"},
    )

    assert event.attempt == 2
    assert event.outcome == "retryable_failure"
    assert event.error_class == "SchemaServiceUnavailable"
    assert event.error_message == "503"
    assert event.extra == {"hint": "upstream busy"}


def test_now_isoformat_produces_z_suffix() -> None:
    value = now_isoformat()
    assert value.endswith("Z")
    assert "+00:00" not in value


# ---- JsonLinesAuditWriter ------------------------------------------------


def test_writer_creates_parent_directory(tmp_path: Path) -> None:
    nested = tmp_path / "deep" / "audit.jsonl"
    writer = JsonLinesAuditWriter(nested)

    assert nested.parent.is_dir()
    assert writer.path == nested


def test_writer_appends_one_line_per_event(tmp_path: Path) -> None:
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    writer.write(build_event(run_id="r1", event="run_started"))
    writer.write(build_event(run_id="r1", event="attempt_started", attempt=1))
    writer.write(
        build_event(
            run_id="r1",
            event="run_succeeded",
            outcome="success",
            summary={"table_count": 3, "column_count": 9, "attempts": 1},
        )
    )

    lines = _read_lines(path)
    assert len(lines) == 3
    assert lines[0]["event"] == "run_started"
    assert lines[1]["event"] == "attempt_started"
    assert lines[1]["attempt"] == 1
    assert lines[2]["event"] == "run_succeeded"
    assert lines[2]["summary"] == {"table_count": 3, "column_count": 9, "attempts": 1}
    # Every emitted line carries schema_version + run_id + timestamp
    for line in lines:
        assert line["schema_version"] == SCHEMA_VERSION
        assert line["run_id"] == "r1"
        assert line["timestamp"].endswith("Z")


def test_writer_appends_to_existing_file(tmp_path: Path) -> None:
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    writer.write(build_event(run_id="r1", event="run_started"))
    # Second writer instance, same path — append, do not truncate.
    writer2 = JsonLinesAuditWriter(path)
    writer2.write(build_event(run_id="r2", event="run_started"))

    lines = _read_lines(path)
    assert [line["run_id"] for line in lines] == ["r1", "r2"]


def test_writer_omits_none_optional_fields(tmp_path: Path) -> None:
    """``None``-valued optional fields must not surface as null in JSON."""
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    writer.write(build_event(run_id="r1", event="run_started"))

    lines = _read_lines(path)
    payload = lines[0]
    assert "attempt" not in payload
    assert "outcome" not in payload
    assert "summary" not in payload
    assert "error_class" not in payload
    assert "error_message" not in payload
    # Empty extras are also dropped
    assert "extra" not in payload


def test_writer_includes_extra_when_populated(tmp_path: Path) -> None:
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    writer.write(
        build_event(
            run_id="r1",
            event="attempt_failed",
            attempt=1,
            outcome="retryable_failure",
            extra={"latency_ms": 42, "host": "schema-service.test:8096"},
        )
    )

    lines = _read_lines(path)
    assert lines[0]["extra"] == {"latency_ms": 42, "host": "schema-service.test:8096"}


def test_writer_truncates_long_error_message(tmp_path: Path) -> None:
    """``error_message`` payload is bounded so accidental leakage stays small."""
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    long_message = "x" * (MAX_ERROR_MESSAGE_LENGTH + 50)
    writer.write(
        build_event(
            run_id="r1",
            event="run_failed",
            outcome="terminal_failure",
            error_class="SchemaServiceMalformedResponse",
            error_message=long_message,
        )
    )

    lines = _read_lines(path)
    truncated = lines[0]["error_message"]
    assert isinstance(truncated, str)
    assert len(truncated) == MAX_ERROR_MESSAGE_LENGTH
    assert truncated.endswith("…")


def test_writer_writes_compact_single_line_json(tmp_path: Path) -> None:
    """No internal newlines in a line — JSON Lines parsers can split safely."""
    path = tmp_path / "audit.jsonl"
    writer = JsonLinesAuditWriter(path)
    writer.write(
        build_event(
            run_id="r1",
            event="attempt_failed",
            attempt=1,
            outcome="retryable_failure",
            error_class="SchemaServiceUnavailable",
            error_message="line1\nline2",  # contains literal newline char
        )
    )

    raw = path.read_text(encoding="utf-8")
    # Exactly one line in the file (one trailing newline → exactly one
    # event line followed by an empty string after split).
    assert raw.count("\n") == 1
    parsed = json.loads(raw.strip())
    # The newline inside the error_message must be JSON-escaped, not
    # passed through as a real linebreak.
    assert parsed["error_message"] == "line1\nline2"


def test_audit_event_frozen() -> None:
    event = AuditEvent(
        schema_version=SCHEMA_VERSION,
        timestamp="2026-05-15T12:34:56.789Z",
        run_id="r1",
        event="run_started",
    )
    with pytest.raises(AttributeError):
        event.event = "other"  # type: ignore[misc]
