#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
REPORT_DIR="${ROOT_DIR}/test-results/security/dependency-check"
mkdir -p "${REPORT_DIR}"

DC_VERSION="${DEPENDENCY_CHECK_VERSION:-10.0.3}"
FAIL_CVSS="${DEPENDENCY_CHECK_FAIL_CVSS:-7.0}"

echo "[security][dependency-check] Running OWASP Dependency-Check v${DC_VERSION} (fail on CVSS >= ${FAIL_CVSS})"

mvn -B \
  org.owasp:dependency-check-maven:"${DC_VERSION}":aggregate \
  -DskipTests=true \
  -Dformat=ALL \
  -DoutputDirectory="${REPORT_DIR}" \
  -DfailOnError=true \
  -DfailBuildOnCVSS="${FAIL_CVSS}"

echo "[security][dependency-check] Reports stored under ${REPORT_DIR}"
