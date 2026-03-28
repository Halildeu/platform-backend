#!/usr/bin/env python3
from __future__ import annotations

import argparse
import json
import socket
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
    expected_statuses: tuple[int, ...] = (200,)


@dataclass(frozen=True)
class TcpCheck:
    host: str
    port: int


@dataclass(frozen=True)
class GatewayRouteCheck:
    service: str
    check: HttpCheck


APP_HEALTH_CHECKS: dict[str, HttpCheck] = {
    "discovery-server": HttpCheck("http://127.0.0.1:8761/actuator/health"),
    "auth-service": HttpCheck("http://127.0.0.1:8088/actuator/health"),
    "permission-service": HttpCheck("http://127.0.0.1:8090/actuator/health"),
    "user-service": HttpCheck("http://127.0.0.1:8089/actuator/health"),
    "variant-service": HttpCheck("http://127.0.0.1:8091/actuator/health"),
    "core-data-service": HttpCheck("http://127.0.0.1:8092/actuator/health"),
    "api-gateway": HttpCheck("http://127.0.0.1:8080/actuator/health"),
}

INFRA_CHECKS: dict[str, HttpCheck | TcpCheck] = {
    "postgres-db": TcpCheck("127.0.0.1", 5432),
    "keycloak": HttpCheck("http://127.0.0.1:8081/realms/master"),
    "vault": HttpCheck("http://127.0.0.1:8200/v1/sys/health"),
}

GATEWAY_ROUTE_CHECKS: dict[str, GatewayRouteCheck] = {
    "gateway-user-by-email-route": GatewayRouteCheck(
        service="user-service",
        check=HttpCheck("http://127.0.0.1:8080/api/v1/users/by-email?email=admin%40example.com", expected_statuses=(200, 401)),
    ),
    "gateway-theme-registry-route": GatewayRouteCheck(
        service="variant-service",
        check=HttpCheck("http://127.0.0.1:8080/api/v1/theme-registry"),
    ),
    "gateway-roles-route": GatewayRouteCheck(
        service="permission-service",
        check=HttpCheck("http://127.0.0.1:8080/api/v1/roles", expected_statuses=(401,)),
    ),
    "gateway-permissions-route": GatewayRouteCheck(
        service="permission-service",
        check=HttpCheck("http://127.0.0.1:8080/api/v1/permissions", expected_statuses=(401,)),
    ),
    "gateway-audit-route": GatewayRouteCheck(
        service="permission-service",
        check=HttpCheck("http://127.0.0.1:8080/api/audit/events?page=0&size=1", expected_statuses=(401,)),
    ),
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

IGNORED_ERROR_MARKERS = (
    "Unable to load io.netty.resolver.dns.macos.MacOSDnsServerAddressStreamProvider",
    "Failed to execute goal org.springframework.boot:spring-boot-maven-plugin",
    "Re-run Maven using the -X switch",
    "re-run Maven with the -e switch",
    "For more information about the errors and possible solutions",
    "[Help 1] http://cwiki.apache.org",
    "MojoExecutionException",
    "GlobalExceptionHandler",
    "RestExceptionHandler",
)

IGNORED_WARNING_MARKERS = (
    "BeanPostProcessorChecker",
    "not eligible for getting processed by all BeanPostProcessors",
    "Ignoring onDemand update due to rate limiter",
    "cancel failed because Lease is not registered",
    "missing entry.",
    "The replication of task UNKNOWN/",
)


def _now_iso_utc() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z")


def _repo_root() -> Path:
    return Path(__file__).resolve().parents[3]


def _load_json(path: Path) -> Any:
    return json.loads(path.read_text(encoding="utf-8"))


def _parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--session-file",
        default=".cache/runtime_guard/backend_start_session.v1.json",
    )
    parser.add_argument(
        "--report",
        default=".cache/reports/backend_runtime_guard.v1.json",
    )
    parser.add_argument("--wait-seconds", type=int, default=90)
    parser.add_argument("--poll-interval", type=float, default=2.0)
    parser.add_argument("--strict-warnings", action="store_true")
    return parser.parse_args(argv)


def _resolve_path(root: Path, raw_path: str) -> Path:
    path = Path(str(raw_path).strip())
    return (root / path).resolve() if not path.is_absolute() else path.resolve()


def _http_check(check: HttpCheck, timeout_seconds: float = 3.0) -> dict[str, Any]:
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

    normalized_status = "UP" if status_code in check.expected_statuses else "DOWN"
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
        "expected_statuses": list(check.expected_statuses),
        "payload_status": payload_status,
        "url": check.url,
        "body_tail": body[-400:] if body else "",
        "json": payload_obj,
    }


def _tcp_check(check: TcpCheck, timeout_seconds: float = 1.0) -> dict[str, Any]:
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


def _wait_for_checks(
    checks: dict[str, HttpCheck | TcpCheck],
    *,
    wait_seconds: int,
    poll_interval: float,
) -> dict[str, dict[str, Any]]:
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


def _find_last_session_line(lines: list[str]) -> int:
    """Return the index of the last '[session]' marker, or 0 if none found."""
    for i in range(len(lines) - 1, -1, -1):
        if lines[i].startswith("[session]"):
            return i
    return 0


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
    start_from = _find_last_session_line(lines)
    error_matches: list[dict[str, Any]] = []
    warning_matches: list[dict[str, Any]] = []
    ignored_warning_matches: list[dict[str, Any]] = []
    ignored_error_matches: list[dict[str, Any]] = []
    for idx, raw_line in enumerate(lines, start=1):
        if idx - 1 < start_from:
            continue
        line = raw_line.rstrip()
        if not line.strip():
            continue
        entry = {"line": idx, "text": line[:400]}
        if _line_matches_error(line):
            stripped = line.replace("[ERROR]", "").strip()
            if not stripped or _line_is_ignored_error(line):
                ignored_error_matches.append(entry)
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
        "ignored_error_matches": ignored_error_matches[-20:],
        "warning_matches": warning_matches[-20:],
        "ignored_warning_matches": ignored_warning_matches[-20:],
    }


def _default_session(root: Path) -> dict[str, Any]:
    backend_root = root / "backend"
    services: list[dict[str, Any]] = []
    for name, check in APP_HEALTH_CHECKS.items():
        if isinstance(check, HttpCheck):
            port_value = int(check.url.split(":")[2].split("/")[0])
        else:
            port_value = int(check.port)
        services.append(
            {
                "name": name,
                "port": port_value,
                "status": "unknown",
                "log_path": str((backend_root / "logs" / f"{name}.log").resolve()),
            }
        )
    return {
        "version": "v1",
        "kind": "backend-start-session",
        "session_id": None,
        "created_at": None,
        "services": services,
    }


def _wait_for_health(service_names: list[str], wait_seconds: int, poll_interval: float) -> dict[str, dict[str, Any]]:
    checks = {name: APP_HEALTH_CHECKS[name] for name in service_names if name in APP_HEALTH_CHECKS}
    return _wait_for_checks(checks, wait_seconds=wait_seconds, poll_interval=poll_interval)


def main(argv: list[str] | None = None) -> int:
    args = _parse_args(argv)
    root = _repo_root()
    session_path = _resolve_path(root, args.session_file)
    report_path = _resolve_path(root, args.report)
    report_path.parent.mkdir(parents=True, exist_ok=True)

    if session_path.exists():
        try:
            session = _load_json(session_path)
        except Exception:
            session = _default_session(root)
    else:
        session = _default_session(root)

    service_entries = session.get("services") if isinstance(session, dict) else None
    if not isinstance(service_entries, list):
        service_entries = []

    selected_services = [
        entry
        for entry in service_entries
        if isinstance(entry, dict)
        and str(entry.get("name") or "") in APP_HEALTH_CHECKS
        and str(entry.get("status") or "unknown") != "filtered"
    ]
    selected_names = [str(entry.get("name")) for entry in selected_services]
    health_results = _wait_for_health(selected_names, args.wait_seconds, args.poll_interval)

    app_reports: list[dict[str, Any]] = []
    total_error_matches = 0
    total_warning_matches = 0
    total_ignored_warning_matches = 0
    failed_services: list[str] = []

    for entry in selected_services:
        name = str(entry.get("name"))
        startup_status = str(entry.get("status") or "unknown")
        log_path_raw = str(entry.get("log_path") or "")
        log_path = Path(log_path_raw) if log_path_raw else root / "backend" / "logs" / f"{name}.log"
        health = health_results.get(name) or {"status": "DOWN", "reachable": False}
        log_scan = _scan_log(log_path) if startup_status == "started" else {
            "performed": False,
            "reason": f"log_scan_skipped:{startup_status}",
            "log_path": str(log_path),
            "error_matches": [],
            "warning_matches": [],
            "ignored_warning_matches": [],
        }
        total_error_matches += len(log_scan.get("error_matches") or [])
        total_warning_matches += len(log_scan.get("warning_matches") or [])
        total_ignored_warning_matches += len(log_scan.get("ignored_warning_matches") or [])
        if health.get("status") != "UP" or (log_scan.get("error_matches") or []):
            failed_services.append(name)

        app_reports.append(
            {
                "name": name,
                "startup_status": startup_status,
                "health": health,
                "log_scan": log_scan,
            }
        )

    infra_results = _wait_for_checks(INFRA_CHECKS, wait_seconds=args.wait_seconds, poll_interval=args.poll_interval)
    infra_reports: list[dict[str, Any]] = []
    failed_infra: list[str] = []
    for name in INFRA_CHECKS:
        result = infra_results.get(name) or {"status": "DOWN", "reachable": False}
        if result.get("status") != "UP":
            failed_infra.append(name)
        infra_reports.append({"name": name, "health": result})

    gateway_route_reports: list[dict[str, Any]] = []
    failed_gateway_routes: list[str] = []
    if "api-gateway" in selected_names:
        selected_gateway_routes = {
            name: route_check.check
            for name, route_check in GATEWAY_ROUTE_CHECKS.items()
            if route_check.service in selected_names
        }
        gateway_results = _wait_for_checks(
            selected_gateway_routes,
            wait_seconds=args.wait_seconds,
            poll_interval=args.poll_interval,
        )
        for name, route_check in GATEWAY_ROUTE_CHECKS.items():
            if route_check.service not in selected_names:
                continue
            result = gateway_results.get(name) or {"status": "DOWN", "reachable": False}
            if result.get("status") != "UP":
                failed_gateway_routes.append(name)
            gateway_route_reports.append(
                {
                    "name": name,
                    "service": route_check.service,
                    "health": result,
                }
            )

    status = "OK"
    if failed_services or failed_infra or failed_gateway_routes or total_error_matches:
        status = "FAIL"
    elif total_warning_matches:
        status = "FAIL" if bool(args.strict_warnings) else "WARN"

    report = {
        "version": "v1",
        "kind": "backend-runtime-guard-report",
        "generated_at": _now_iso_utc(),
        "status": status,
        "strict_warnings": bool(args.strict_warnings),
        "session_file": str(session_path),
        "report_path": str(report_path),
        "summary": {
            "services_checked": len(app_reports),
            "infra_checked": len(infra_reports),
            "failed_services": failed_services,
            "failed_infra": failed_infra,
            "gateway_routes_checked": len(gateway_route_reports),
            "failed_gateway_routes": failed_gateway_routes,
            "error_match_count": total_error_matches,
            "warning_match_count": total_warning_matches,
            "ignored_warning_match_count": total_ignored_warning_matches,
        },
        "session": {
            "session_id": session.get("session_id") if isinstance(session, dict) else None,
            "created_at": session.get("created_at") if isinstance(session, dict) else None,
            "selected_filter": session.get("selected_filter") if isinstance(session, dict) else None,
        },
        "apps": app_reports,
        "infra": infra_reports,
        "gateway_routes": gateway_route_reports,
    }

    report_path.write_text(json.dumps(report, ensure_ascii=False, sort_keys=True, indent=2) + "\n", encoding="utf-8")
    print(
        json.dumps(
            {
                "status": status,
                "report_path": str(report_path),
                "failed_services": failed_services,
                "failed_infra": failed_infra,
                "failed_gateway_routes": failed_gateway_routes,
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
