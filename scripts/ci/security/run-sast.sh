#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND_POM="${ROOT_DIR}/pom.xml"
REPORT_DIR="${ROOT_DIR}/test-results/security"
mkdir -p "${REPORT_DIR}"
cd "${ROOT_DIR}"

SPOTBUGS_VERSION="${SPOTBUGS_VERSION:-4.8.6.2}"
echo "[security][sast] Running SpotBugs scan with version ${SPOTBUGS_VERSION}"

mvn -B \
  -f "${BACKEND_POM}" \
  package \
  com.github.spotbugs:spotbugs-maven-plugin:"${SPOTBUGS_VERSION}":spotbugs \
  -DskipTests=true \
  -Dspotbugs.effort=Max \
  -Dspotbugs.threshold=Low \
  -Dspotbugs.failOnError=true \
  -Dspotbugs.outputDirectory="${REPORT_DIR}/spotbugs"

echo "[security][sast] SpotBugs report generated under ${REPORT_DIR}/spotbugs"
