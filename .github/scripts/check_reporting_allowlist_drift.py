#!/usr/bin/env python3
"""Reporting allowlist mirror drift guard (Adım 12 PR-4a follow-up).

Codex `019e2d64` S3 + follow-up REVISE: the Workcube reporting
source-table allowlist exists in TWO places in this monorepo —

  * report-service  com.example.report.contract.schema.ReportingAllowlist
  * schema-service  com.example.schema.contract.SchemaReportingAllowlist

schema-service must NOT take a build dependency on report-service
(it is standalone-built), so the 40-table V1 set is an intentional
in-repo mirror. The schema-service unit test pins count + a sample,
but that is not a real drift guard: swap one table for another and
the count test still passes.

This script is the real guard. It parses the `V1 = Set.of(...)`
literal set from BOTH Java sources and hard-fails on ANY difference
(exact two-way set equality), plus pins the schema-service
`NAME` / `VERSION` contract constants.

Fail-closed parser discipline (Codex follow-up REVISE):
  * file unreadable                              -> fail
  * `V1 = Set.of(` marker absent                 -> fail
  * unbalanced parentheses after the marker      -> fail
  * Java line/block comments stripped before     (a future `// "FOO"`
    string extraction                             comment must not be
                                                   mis-parsed as a table)
  * extracted set empty                          -> fail
  * duplicate table literal                      -> fail
  * report-service class / V1 field marker absent-> fail

Exit codes: 0 = mirrors match, 1 = drift / parse failure.

No path filter on the calling workflow — the script is milliseconds
of work, so it runs on every PR (Codex: a path-filtered *required*
check risks the "missing/pending" merge-block trap).
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

# Repo-root-relative paths. The workflow runs from the checkout root.
REPORT_SERVICE_ALLOWLIST = Path(
    "report-service/src/main/java/com/example/report/contract/schema/ReportingAllowlist.java"
)
SCHEMA_SERVICE_ALLOWLIST = Path(
    "schema-service/src/main/java/com/example/schema/contract/SchemaReportingAllowlist.java"
)

EXPECTED_COUNT = 40
EXPECTED_NAME = "ReportingAllowlist"
EXPECTED_VERSION = "V1"

_TABLE_TOKEN = re.compile(r"^[A-Z][A-Z0-9_]*$")
_STRING_LITERAL = re.compile(r'"([^"\\]*)"')
_LINE_COMMENT = re.compile(r"//[^\n]*")
_BLOCK_COMMENT = re.compile(r"/\*.*?\*/", re.DOTALL)
_V1_MARKER = re.compile(r"Set<String>\s+V1\s*=\s*Set\.of\s*\(")


class DriftError(RuntimeError):
    """Raised on any parse failure or mirror drift."""


def _read(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except OSError as exc:
        raise DriftError(f"cannot read {path}: {exc}") from exc


def _strip_comments(source: str) -> str:
    """Remove Java block + line comments so a commented-out string
    literal can never be mis-parsed as a table name."""
    without_block = _BLOCK_COMMENT.sub(" ", source)
    return _LINE_COMMENT.sub("", without_block)


def extract_v1(path: Path) -> set[str]:
    """Parse the `V1 = Set.of(...)` table set from a Java allowlist file.

    Fail-closed: every structural assumption that does not hold raises
    :class:`DriftError` rather than silently returning a partial set.
    """
    source = _strip_comments(_read(path))

    marker = _V1_MARKER.search(source)
    if marker is None:
        raise DriftError(
            f"{path}: `Set<String> V1 = Set.of(` marker not found "
            "(field renamed or removed?)"
        )

    # Walk from the opening paren to its matching close, tracking depth.
    open_idx = source.index("(", marker.start())
    depth = 0
    close_idx = -1
    for i in range(open_idx, len(source)):
        ch = source[i]
        if ch == "(":
            depth += 1
        elif ch == ")":
            depth -= 1
            if depth == 0:
                close_idx = i
                break
    if close_idx < 0:
        raise DriftError(
            f"{path}: unbalanced parentheses after `Set.of(` — "
            "cannot determine the V1 block"
        )

    block = source[open_idx + 1 : close_idx]
    literals = _STRING_LITERAL.findall(block)
    if not literals:
        raise DriftError(f"{path}: V1 set is empty after parsing")

    tables: set[str] = set()
    for literal in literals:
        if not _TABLE_TOKEN.match(literal):
            raise DriftError(
                f"{path}: literal {literal!r} is not a valid UPPER_SNAKE "
                "table token"
            )
        if literal in tables:
            raise DriftError(
                f"{path}: duplicate table literal {literal!r} in V1 set"
            )
        tables.add(literal)
    return tables


def check_report_service_markers(path: Path) -> None:
    """report-service has no NAME/VERSION constants, but the class +
    V1 field markers must exist — a rename there must not slip past."""
    source = _read(path)
    if "public final class ReportingAllowlist" not in source:
        raise DriftError(
            f"{path}: `public final class ReportingAllowlist` not found"
        )
    if _V1_MARKER.search(_strip_comments(source)) is None:
        raise DriftError(f"{path}: `Set<String> V1 = Set.of(` not found")


def check_schema_service_constants(path: Path) -> None:
    """schema-service NAME / VERSION feed the wire contract's
    `allowlist_name` / `allowlist_version` — pin them exactly."""
    source = _strip_comments(_read(path))
    name_ok = re.search(
        rf'\bNAME\s*=\s*"{re.escape(EXPECTED_NAME)}"', source
    )
    version_ok = re.search(
        rf'\bVERSION\s*=\s*"{re.escape(EXPECTED_VERSION)}"', source
    )
    if name_ok is None:
        raise DriftError(
            f'{path}: NAME constant is not = "{EXPECTED_NAME}"'
        )
    if version_ok is None:
        raise DriftError(
            f'{path}: VERSION constant is not = "{EXPECTED_VERSION}"'
        )


def main() -> int:
    try:
        report_v1 = extract_v1(REPORT_SERVICE_ALLOWLIST)
        schema_v1 = extract_v1(SCHEMA_SERVICE_ALLOWLIST)
        check_report_service_markers(REPORT_SERVICE_ALLOWLIST)
        check_schema_service_constants(SCHEMA_SERVICE_ALLOWLIST)
    except DriftError as exc:
        print(f"::error::reporting-allowlist drift guard: {exc}")
        return 1

    missing_in_schema = sorted(report_v1 - schema_v1)
    extra_in_schema = sorted(schema_v1 - report_v1)

    if missing_in_schema or extra_in_schema:
        print("::error::ReportingAllowlist.V1 drift detected")
        if missing_in_schema:
            print("Missing in schema-service SchemaReportingAllowlist.V1:")
            for table in missing_in_schema:
                print(f"  - {table}")
        if extra_in_schema:
            print("Extra in schema-service SchemaReportingAllowlist.V1:")
            for table in extra_in_schema:
                print(f"  - {table}")
        print(
            "Fix: keep report-service ReportingAllowlist.V1 and "
            "schema-service SchemaReportingAllowlist.V1 byte-identical."
        )
        return 1

    count = len(report_v1)
    if count != EXPECTED_COUNT:
        print(
            f"::error::ReportingAllowlist.V1 has {count} tables, "
            f"expected {EXPECTED_COUNT}"
        )
        return 1

    print(
        f"OK: ReportingAllowlist.V1 mirrors SchemaReportingAllowlist.V1 "
        f"exactly ({count} tables); NAME/VERSION contract constants pinned."
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
