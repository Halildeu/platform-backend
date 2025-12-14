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

echo "[security][dast] Pulling ${ZAP_IMAGE}:${ZAP_TAG}"
docker pull "${ZAP_IMAGE}:${ZAP_TAG}" >/dev/null

echo "[security][dast] Running ZAP Baseline scan against ${TARGET_URL}"
docker run --rm \
  -v "${REPORT_DIR}:/zap/wrk" \
  "${ZAP_IMAGE}:${ZAP_TAG}" \
  zap-baseline.py -t "${TARGET_URL}" -x zap-report.xml -J zap-report.json -r zap-report.html

echo "[security][dast] Reports stored under ${REPORT_DIR}"
