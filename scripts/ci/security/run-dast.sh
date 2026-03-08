#!/usr/bin/env bash
set -euo pipefail

TARGET_URL="${ZAP_TARGET_URL:-}"
REPORT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/test-results/security/zap"
mkdir -p "${REPORT_DIR}"

if [[ -z "${TARGET_URL}" ]]; then
  TARGET_URL="${DAST_FALLBACK_URL:-https://example.com}"
  echo "::warning::ZAP_TARGET_URL not set; falling back to ${TARGET_URL}. Set secret ZAP_TARGET_URL for real scans."
fi

ZAP_IMAGE="${ZAP_IMAGE:-owasp/zap2docker-stable}"
ZAP_TAG="${ZAP_TAG:-latest}"
AUTH_MODE="${ZAP_AUTH_MODE:-none}"
ZAP_EXTRA_ARGS=()

case "${AUTH_MODE}" in
  ""|none)
    AUTH_MODE="none"
    ;;
  bearer_header)
    if [[ -z "${ZAP_AUTH_HEADER_NAME:-}" || -z "${ZAP_AUTH_HEADER_VALUE:-}" ]]; then
      echo "[security][dast] HATA: ZAP_AUTH_MODE=bearer_header icin ZAP_AUTH_HEADER_NAME ve ZAP_AUTH_HEADER_VALUE zorunlu." >&2
      exit 1
    fi
    ZAP_EXTRA_ARGS+=(
      -z
      "-config replacer.full_list(0).description=auth-header -config replacer.full_list(0).enabled=true -config replacer.full_list(0).matchtype=REQ_HEADER -config replacer.full_list(0).matchstr=${ZAP_AUTH_HEADER_NAME} -config replacer.full_list(0).replacement=${ZAP_AUTH_HEADER_VALUE}"
    )
    ;;
  *)
    echo "[security][dast] HATA: desteklenmeyen ZAP_AUTH_MODE=${AUTH_MODE}" >&2
    exit 1
    ;;
esac

echo "[security][dast] Pulling ${ZAP_IMAGE}:${ZAP_TAG}"
docker pull "${ZAP_IMAGE}:${ZAP_TAG}" >/dev/null

echo "[security][dast] Running ZAP Baseline scan against ${TARGET_URL}"
docker_cmd=(
  docker run --rm
  -v "${REPORT_DIR}:/zap/wrk"
  "${ZAP_IMAGE}:${ZAP_TAG}"
  zap-baseline.py -t "${TARGET_URL}" -x zap-report.xml -J zap-report.json -r zap-report.html
)

if [[ "${#ZAP_EXTRA_ARGS[@]}" -gt 0 ]]; then
  docker_cmd+=("${ZAP_EXTRA_ARGS[@]}")
fi

"${docker_cmd[@]}"

echo "[security][dast] Reports stored under ${REPORT_DIR}"
