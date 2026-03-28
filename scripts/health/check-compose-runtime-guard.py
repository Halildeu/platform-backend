#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import socket
import subprocess
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen


@dataclass(frozen=True)
class HttpCheck:
    url: str
    expected_status: int = 200


@dataclass(frozen=True)
class TcpCheck:
    host: str
    port: int


SERVICE_CHECKS: dict[str, HttpCheck | TcpCheck] = {
    "discovery-server": HttpCheck("http://127.0.0.1:8761/actuator/health"),
    "postgres-db": TcpCheck("127.0.0.1", 5432),
    "user-service": HttpCheck("http://127.0.0.1:8089/actuator/health"),
    "auth-service": HttpCheck("http://127.0.0.1:8088/actuator/health"),
    "variant-service": HttpCheck("http://127.0.0.1:8091/actuator/health"),
    "core-data-service": HttpCheck("http://127.0.0.1:8092/actuator/health"),
    "api-gateway": HttpCheck("http://127.0.0.1:8080/actuator/health"),
    "keycloak": HttpCheck("http://127.0.0.1:8081/realms/serban"),
    "vault": HttpCheck("http://127.0.0.1:8200/v1/sys/health"),
    "permission-service": HttpCheck("http://127.0.0.1:8090/actuator/health"),
    "observability-prometheus": HttpCheck("http://127.0.0.1:9090/-/healthy"),
    "observability-grafana": HttpCheck("http://127.0.0.1:3010/api/health"),
}

FATAL_MARKERS = (
    "APPLICATION FAILED TO START",
    "Error starting ApplicationContext",
    "BeanCreationException",
    "UnsatisfiedDependencyException",
    "FlywayException",
    "Failed to execute goal",
    "Process terminated with exit code",
    "VaultConfigInitializationException",
    "Failed to obtain JDBC Connection",
    "Error creating bean with name",
)

IGNORED_WARNING_MARKERS = (
    "BeanPostProcessorChecker",
    "not eligible for getting processed by all BeanPostProcessors",
    "Ignoring onDemand update due to rate limiter",
    "cancel failed because Lease is not registered",
    "missing entry.",
    "The replication of task UNKNOWN/",
    "Running the server in development mode. DO NOT use this configuration in production.",
    "Unable to persist Infinispan internal caches as no global state enabled",
    "transaction recovery is not enabled",
    "heartbeat timeout reached, starting election",
    "skipping new raft TLS config creation",
)

HEALTHY_CONTAINER_STATES = {"running", "healthy"}
TERMINAL_BAD_CONTAINER_STATES = {"exited", "dead", "missing"}
IGNORED_ERROR_MARKERS = (
    "SMTP not configured, check your grafana.ini config file's [smtp] section",
)


def _now_iso_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _resolve_path(root: Path, raw_path: str) -> Path:
    path = Path(str(raw_path).strip())
    return (root / path).resolve() if not path.is_absolute() else path.resolve()


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--session-file",
        default=".cache/runtime_guard/backend_compose_session.v1.json",
    )
    parser.add_argument(
        "--report",
        default=".cache/reports/backend_compose_runtime_guard.v1.json",
    )
    parser.add_argument("--wait-seconds", type=int, default=180)
    parser.add_argument("--poll-interval", type=float, default=3.0)
    parser.add_argument("--strict-warnings", action="store_true")
    return parser.parse_args(argv)


def _http_check(check: HttpCheck, timeout_seconds: float = 4.0) -> dict[str, Any]:
    request = Request(check.url, headers={"Accept": "application/json"})
    try:
        with urlopen(request, timeout=timeout_seconds) as response:
            status_code = int(response.getcode())
            body = response.read().decode("utf-8", errors="replace")
    except HTTPError as exc:
        status_code = int(exc.code)
        body = exc.read().decode("utf-8", errors="replace")
    except URLError as exc:
        return {
            "reachable": False,
            "status": "DOWN",
            "error": str(exc.reason),
            "url": check.url,
        }
    except Exception as exc:  # pragma: no cover - defensive
        return {
            "reachable": False,
            "status": "DOWN",
            "error": str(exc),
            "url": check.url,
        }

    normalized_status = "UP" if status_code == check.expected_status else "DOWN"
    payload_status = None
    try:
        payload_obj = json.loads(body)
        payload_status = payload_obj.get("status")
        if isinstance(payload_status, str) and payload_status.upper() == "UP":
            normalized_status = "UP"
    except Exception:
        payload_obj = None

    return {
        "reachable": True,
        "status": normalized_status,
        "http_status": status_code,
        "payload_status": payload_status,
        "url": check.url,
        "body_tail": body[-400:] if body else "",
        "json": payload_obj,
    }


def _tcp_check(check: TcpCheck, timeout_seconds: float = 1.5) -> dict[str, Any]:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.settimeout(timeout_seconds)
        try:
            sock.connect((check.host, int(check.port)))
        except OSError as exc:
            return {
                "reachable": False,
                "status": "DOWN",
                "error": str(exc),
                "host": check.host,
                "port": int(check.port),
            }
    return {
        "reachable": True,
        "status": "UP",
        "host": check.host,
        "port": int(check.port),
    }


def _check_service(name: str, check: HttpCheck | TcpCheck) -> dict[str, Any]:
    if isinstance(check, HttpCheck):
        return _http_check(check)
    return _tcp_check(check)


def _wait_for_health(service_names: list[str], wait_seconds: int, poll_interval: float) -> dict[str, dict[str, Any]]:
    checks = {name: SERVICE_CHECKS[name] for name in service_names if name in SERVICE_CHECKS}
    results: dict[str, dict[str, Any]] = {name: {"status": "DOWN", "reachable": False} for name in checks}
    deadline = time.time() + max(wait_seconds, 1)
    while time.time() <= deadline:
        pending = 0
        for name, check in checks.items():
            result = _check_service(name, check)
            results[name] = result
            if result.get("status") != "UP":
                pending += 1
        if pending == 0:
            break
        time.sleep(max(poll_interval, 0.5))
    return results


def _line_matches_warning(line: str) -> bool:
    upper = line.upper()
    return " WARN " in upper or upper.startswith("WARN ") or "[WARN]" in upper or "[WARNING]" in upper


def _line_matches_error(line: str) -> bool:
    upper = line.upper()
    if "[ERROR]" in upper or " ERROR " in upper:
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
            "error_matches": [],
            "warning_matches": [],
            "ignored_warning_matches": [],
            "reason": "log_missing",
            "log_path": str(path),
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
        "error_matches": error_matches[-20:],
        "warning_matches": warning_matches[-20:],
        "ignored_warning_matches": ignored_warning_matches[-20:],
    }


def _run_command(command: list[str], cwd: Path) -> subprocess.CompletedProcess[str]:
    return subprocess.run(command, cwd=cwd, text=True, capture_output=True, check=False)


def _parse_compose_ps(raw_text: str) -> list[dict[str, Any]]:
    text = str(raw_text or "").strip()
    if not text:
        return []
    try:
        parsed = json.loads(text)
    except json.JSONDecodeError:
        entries: list[dict[str, Any]] = []
        for raw_line in text.splitlines():
            raw_line = raw_line.strip()
            if not raw_line:
                continue
            try:
                obj = json.loads(raw_line)
            except json.JSONDecodeError:
                continue
            if isinstance(obj, dict):
                entries.append(obj)
        return entries
    if isinstance(parsed, list):
        return [entry for entry in parsed if isinstance(entry, dict)]
    if isinstance(parsed, dict):
        return [parsed]
    return []


def _container_state(entry: dict[str, Any]) -> str:
    health = str(entry.get("Health") or entry.get("health") or "").strip().lower()
    if health:
        return health
    state = str(entry.get("State") or entry.get("state") or "").strip().lower()
    if state:
        return state
    status = str(entry.get("Status") or entry.get("status") or "").strip().lower()
    if "(healthy)" in status:
        return "healthy"
    if status.startswith("up"):
        return "running"
    if status.startswith("exited"):
        return "exited"
    return status


def _service_name(entry: dict[str, Any]) -> str:
    for key in ("Service", "service", "Name", "name"):
        raw = str(entry.get(key) or "").strip()
        if raw:
            return raw
    return ""


def _collect_compose_ps(compose_file: Path, report_path: Path) -> tuple[list[dict[str, Any]], str]:
    proc = _run_command(
        ["docker", "compose", "-f", str(compose_file), "ps", "--format", "json"],
        cwd=compose_file.parent,
    )
    raw_output = proc.stdout if proc.returncode == 0 else proc.stderr
    parsed = _parse_compose_ps(raw_output)
    payload = {
        "version": "v1",
        "kind": "backend-compose-ps-report",
        "generated_at": _now_iso_utc(),
        "compose_file": str(compose_file),
        "return_code": int(proc.returncode),
        "entries": parsed,
        "raw_tail": str(raw_output or "")[-4000:],
    }
    report_path.parent.mkdir(parents=True, exist_ok=True)
    report_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return parsed, str(raw_output or "")


def _collect_compose_logs(compose_file: Path, created_at: str, selected_services: list[dict[str, Any]]) -> None:
    for entry in selected_services:
        service_name = str(entry.get("name") or "").strip()
        log_path_raw = str(entry.get("log_path") or "").strip()
        if not service_name or not log_path_raw:
            continue
        log_path = Path(log_path_raw)
        log_path.parent.mkdir(parents=True, exist_ok=True)
        proc = _run_command(
            [
                "docker",
                "compose",
                "-f",
                str(compose_file),
                "logs",
                "--no-color",
                "--since",
                created_at,
                "--tail",
                "400",
                service_name,
            ],
            cwd=compose_file.parent,
        )
        if proc.returncode == 0:
            content = str(proc.stdout or "")
        else:
            content = str(proc.stdout or "") + str(proc.stderr or "")
        log_path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    root = _repo_root()
    session_path = _resolve_path(root, args.session_file)
    report_path = _resolve_path(root, args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)

    if not session_path.exists():
        payload = {
            "status": "FAIL",
            "error_code": "COMPOSE_SESSION_MISSING",
            "session_file": str(session_path),
        }
        report_path.write_text(json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        print(json.dumps(payload, ensure_ascii=False, sort_keys=True))
        return 2

    session = _load_json(session_path)
    compose_file = _resolve_path(root, str(session.get("compose_file") or "backend/docker-compose.yml"))
    compose_ps_report = _resolve_path(root, str(session.get("compose_ps_report") or ".cache/reports/backend_compose_ps.v1.json"))
    created_at = str(session.get("created_at") or _now_iso_utc())
    service_entries = session.get("services") if isinstance(session, dict) else None
    if not isinstance(service_entries, list):
        service_entries = []

    selected_services = [
        entry
        for entry in service_entries
        if isinstance(entry, dict) and str(entry.get("status") or "unknown") != "filtered"
    ]
    health_service_names = [
        str(entry.get("name") or "")
        for entry in selected_services
        if str(entry.get("name") or "") in SERVICE_CHECKS
    ]
    health_results = _wait_for_health(health_service_names, args.wait_seconds, args.poll_interval)

    compose_ps_entries, compose_ps_raw = _collect_compose_ps(compose_file, compose_ps_report)
    compose_ps_map = {
        _service_name(entry): {
            "entry": entry,
            "container_state": _container_state(entry),
        }
        for entry in compose_ps_entries
        if _service_name(entry)
    }
    _collect_compose_logs(compose_file, created_at, selected_services)

    total_error_matches = 0
    total_warning_matches = 0
    total_ignored_warning_matches = 0
    failed_services: list[str] = []
    service_reports: list[dict[str, Any]] = []

    for entry in selected_services:
        name = str(entry.get("name") or "")
        log_path = Path(str(entry.get("log_path") or ""))
        health = health_results.get(name)
        compose_state = compose_ps_map.get(name) or {"container_state": "missing", "entry": None}
        log_scan = _scan_log(log_path)

        total_error_matches += len(log_scan.get("error_matches") or [])
        total_warning_matches += len(log_scan.get("warning_matches") or [])
        total_ignored_warning_matches += len(log_scan.get("ignored_warning_matches") or [])

        health_down = bool(health) and health.get("status") != "UP"
        container_state = str(compose_state.get("container_state") or "").lower()
        if health:
            unhealthy_container = container_state in TERMINAL_BAD_CONTAINER_STATES
        else:
            unhealthy_container = container_state not in HEALTHY_CONTAINER_STATES
        if health_down or unhealthy_container or (log_scan.get("error_matches") or []):
            failed_services.append(name)

        service_reports.append(
            {
                "name": name,
                "startup_status": str(entry.get("status") or "unknown"),
                "health": health,
                "compose_state": {
                    "container_state": compose_state.get("container_state"),
                    "raw": compose_state.get("entry"),
                },
                "log_scan": log_scan,
            }
        )

    status = "OK"
    if failed_services or total_error_matches:
        status = "FAIL"
    elif total_warning_matches:
        status = "FAIL" if bool(args.strict_warnings) else "WARN"

    report = {
        "version": "v1",
        "kind": "backend-compose-runtime-guard-report",
        "generated_at": _now_iso_utc(),
        "status": status,
        "strict_warnings": bool(args.strict_warnings),
        "session_file": str(session_path),
        "report_path": str(report_path),
        "compose_file": str(compose_file),
        "compose_ps_report": str(compose_ps_report),
        "summary": {
            "services_checked": len(service_reports),
            "failed_services": failed_services,
            "error_match_count": total_error_matches,
            "warning_match_count": total_warning_matches,
            "ignored_warning_match_count": total_ignored_warning_matches,
        },
        "session": {
            "session_id": session.get("session_id") if isinstance(session, dict) else None,
            "created_at": session.get("created_at") if isinstance(session, dict) else None,
            "selected_filter": session.get("selected_filter") if isinstance(session, dict) else None,
        },
        "services": service_reports,
        "compose_ps_raw_tail": str(compose_ps_raw or "")[-4000:],
    }

    report_path.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "status": status,
                "report_path": str(report_path),
                "failed_services": failed_services,
                "error_match_count": total_error_matches,
                "warning_match_count": total_warning_matches,
            },
            ensure_ascii=False,
            sort_keys=True,
        )
    )
    return 0 if status in {"OK", "WARN"} else 2


if __name__ == "__main__":
    raise SystemExit(main())
