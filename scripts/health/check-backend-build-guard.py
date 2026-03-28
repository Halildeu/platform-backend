#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


FATAL_MARKERS = (
    "[ERROR]",
    "BUILD FAILURE",
    "Failed to execute goal",
    "COMPILATION ERROR",
    "Compilation failure",
    "Fatal error compiling",
    "Error executing Maven.",
)

IGNORED_WARNING_MARKERS = (
    "uses unchecked or unsafe operations",
    "Recompile with -Xlint:unchecked for details",
    "Use -Xlint:deprecation for details",
)

IGNORED_ERROR_MARKERS = ()


def _now_iso_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log", required=True)
    parser.add_argument("--report", default=".cache/reports/backend_build_guard.v1.json")
    parser.add_argument("--label", default="compile")
    parser.add_argument("--command", default="./mvnw -q -DskipTests compile")
    parser.add_argument("--strict-warnings", action="store_true")
    return parser.parse_args(argv)


def _line_matches_warning(line: str) -> bool:
    upper = line.upper()
    return "[WARNING]" in upper or upper.startswith("WARNING:") or " WARNING " in upper


def _line_matches_error(line: str) -> bool:
    upper = line.upper()
    if "[ERROR]" in upper or upper.startswith("ERROR:") or " ERROR " in upper:
        return True
    return any(marker.upper() in upper for marker in FATAL_MARKERS)


def _line_is_ignored_warning(line: str) -> bool:
    return any(marker in line for marker in IGNORED_WARNING_MARKERS)


def _line_is_ignored_error(line: str) -> bool:
    return any(marker in line for marker in IGNORED_ERROR_MARKERS)


def _scan_log(path: Path) -> dict[str, Any]:
    if not path.exists():
        return {
            "performed": False,
            "reason": "log_missing",
            "log_path": str(path),
            "error_matches": [],
            "warning_matches": [],
            "ignored_warning_matches": [],
        }

    lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
    error_matches: list[dict[str, Any]] = []
    warning_matches: list[dict[str, Any]] = []
    ignored_warning_matches: list[dict[str, Any]] = []

    for idx, raw_line in enumerate(lines, start=1):
        line = raw_line.rstrip()
        if not line.strip():
            continue
        entry = {"line": idx, "text": line[:400]}
        if _line_matches_error(line):
            if _line_is_ignored_error(line):
                ignored_warning_matches.append(entry)
                continue
            error_matches.append(entry)
            continue
        if _line_matches_warning(line):
            if _line_is_ignored_warning(line):
                ignored_warning_matches.append(entry)
            else:
                warning_matches.append(entry)

    return {
        "performed": True,
        "log_path": str(path),
        "error_matches": error_matches[-50:],
        "warning_matches": warning_matches[-50:],
        "ignored_warning_matches": ignored_warning_matches[-50:],
    }


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    log_path = Path(args.log).resolve()
    report_path = Path(args.report).resolve()
    report_path.parent.mkdir(parents=True, exist_ok=True)

    log_scan = _scan_log(log_path)
    error_match_count = len(log_scan.get("error_matches") or [])
    warning_match_count = len(log_scan.get("warning_matches") or [])
    ignored_warning_match_count = len(log_scan.get("ignored_warning_matches") or [])

    status = "OK"
    if error_match_count:
        status = "FAIL"
    elif warning_match_count:
        status = "FAIL" if bool(args.strict_warnings) else "WARN"

    report = {
        "version": "v1",
        "kind": "backend-build-guard-report",
        "generated_at": _now_iso_utc(),
        "status": status,
        "strict_warnings": bool(args.strict_warnings),
        "label": args.label,
        "command": args.command,
        "report_path": str(report_path),
        "summary": {
            "error_match_count": error_match_count,
            "warning_match_count": warning_match_count,
            "ignored_warning_match_count": ignored_warning_match_count,
        },
        "log_scan": log_scan,
    }

    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "status": status,
                "report_path": str(report_path),
                "error_match_count": error_match_count,
                "warning_match_count": warning_match_count,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if status in {"OK", "WARN"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
