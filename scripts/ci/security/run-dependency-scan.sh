#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND_POM="${ROOT_DIR}/pom.xml"
REPORT_DIR="${ROOT_DIR}/test-results/security/dependency-check"
TARGET_REPORT_DIR="${ROOT_DIR}/target"
mkdir -p "${REPORT_DIR}"
cd "${ROOT_DIR}"

DC_VERSION="${DEPENDENCY_CHECK_VERSION:-12.2.0}"
FAIL_CVSS="${DEPENDENCY_CHECK_FAIL_CVSS:-7.0}"
NVD_API_KEY_VALUE="${NVD_API_KEY:-}"
NVD_API_DELAY_MS="${DEPENDENCY_CHECK_NVD_API_DELAY_MS:-5000}"
NVD_API_MAX_RETRY_COUNT="${DEPENDENCY_CHECK_NVD_MAX_RETRY_COUNT:-40}"
NVD_API_RESULTS_PER_PAGE="${DEPENDENCY_CHECK_NVD_RESULTS_PER_PAGE:-2000}"
CACHE_DIR_SUFFIX="${DC_VERSION//./_}"
LOCAL_DC_CACHE_DIR="${REPORT_DIR}/cache-v${CACHE_DIR_SUFFIX}"
LOCAL_DC_CACHE_SNAPSHOT_DIR="${REPORT_DIR}/cache-snapshot-v${CACHE_DIR_SUFFIX}"
SUPPRESSION_FILE="${ROOT_DIR}/backend/scripts/ci/security/dependency-check-suppressions.xml"
SCAN_LOG_PATH="${REPORT_DIR}/dependency-check.log"

echo "[security][dependency-check] Running OWASP Dependency-Check v${DC_VERSION} (fail on CVSS >= ${FAIL_CVSS})"

mkdir -p "${LOCAL_DC_CACHE_DIR}"

has_cache_data() {
  find "${LOCAL_DC_CACHE_DIR}" -mindepth 1 -print -quit | grep -q .
}

prepare_cache_snapshot() {
  rm -rf "${LOCAL_DC_CACHE_SNAPSHOT_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_SNAPSHOT_DIR}"
  cp -R "${LOCAL_DC_CACHE_DIR}/." "${LOCAL_DC_CACHE_SNAPSHOT_DIR}/"
}

restore_cache_snapshot() {
  rm -rf "${LOCAL_DC_CACHE_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_DIR}"
  cp -R "${LOCAL_DC_CACHE_SNAPSHOT_DIR}/." "${LOCAL_DC_CACHE_DIR}/"
}

has_cache_snapshot() {
  find "${LOCAL_DC_CACHE_SNAPSHOT_DIR}" -mindepth 1 -print -quit | grep -q .
}

scan_log_has_recoverable_nvd_failure() {
  grep -Eq \
    "NVD Returned Status Code: 429|connectionPool is null|MVStoreException|Error updating the NVD Data|JdbcSQLNonTransientException: IO Exception" \
    "${SCAN_LOG_PATH}"
}

run_scan() {
  set +e
  "${cmd[@]}" 2>&1 | tee -a "${SCAN_LOG_PATH}"
  scan_status=${PIPESTATUS[0]}
  set -e
}

build_cmd() {
  local use_cached_data="$1"

  cmd=(
    mvn -B
    -f "${BACKEND_POM}"
    package
    "org.owasp:dependency-check-maven:${DC_VERSION}:aggregate"
    -DskipTests=true
    -Dformats=HTML,JSON,CSV,XML
    -DoutputDirectory="${REPORT_DIR}"
    -DfailOnError=true
    "-DfailBuildOnCVSS=${FAIL_CVSS}"
    "-DdataDirectory=${LOCAL_DC_CACHE_DIR}"
  )

  if [[ -f "${SUPPRESSION_FILE}" ]]; then
    cmd+=("-DsuppressionFile=${SUPPRESSION_FILE}")
  fi

  if [[ -n "${NVD_API_KEY_VALUE}" ]]; then
    echo "[security][dependency-check] NVD_API_KEY bulundu; throttle'li update aciliyor (delay=${NVD_API_DELAY_MS}ms, retries=${NVD_API_MAX_RETRY_COUNT}, pageSize=${NVD_API_RESULTS_PER_PAGE})."
    cmd+=("-DnvdApiKeyEnvironmentVariable=NVD_API_KEY")
    cmd+=("-DnvdApiDelay=${NVD_API_DELAY_MS}")
    cmd+=("-DnvdMaxRetryCount=${NVD_API_MAX_RETRY_COUNT}")
    cmd+=("-DnvdApiResultsPerPage=${NVD_API_RESULTS_PER_PAGE}")
    return
  fi

  if [[ "${use_cached_data}" == "true" ]]; then
    echo "[security][dependency-check] NVD_API_KEY yok; mevcut versioned cache ile auto update kapatiliyor."
    rm -f "${LOCAL_DC_CACHE_DIR}/odc.update.lock"
    cmd+=("-DautoUpdate=false")
    cmd+=("-DossindexAnalyzerEnabled=false")
  else
    echo "[security][dependency-check] NVD_API_KEY ve versioned cache yok; ilk veritabani bootstrap'i icin auto update acik birakiliyor."
  fi
}

cache_present_at_start=false
if has_cache_data; then
  cache_present_at_start=true
fi

if [[ "${cache_present_at_start}" == "true" ]]; then
  prepare_cache_snapshot
fi

build_cmd "${cache_present_at_start}"

scan_status=0
: > "${SCAN_LOG_PATH}"
run_scan

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" ]] && grep -q "Invalid API Key" "${SCAN_LOG_PATH}" && has_cache_data; then
  echo "[security][dependency-check] Gecersiz NVD_API_KEY algilandi; versioned cache ile cache-only modda yeniden deneniyor."
  NVD_API_KEY_VALUE=""
  build_cmd true
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" ]] && grep -q "Invalid API Key" "${SCAN_LOG_PATH}"; then
  echo "[security][dependency-check] Gecersiz NVD_API_KEY reusable cache olmadan geldi; anahtarsiz bootstrap icin versioned cache sifirlaniyor."
  NVD_API_KEY_VALUE=""
  rm -rf "${LOCAL_DC_CACHE_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_DIR}"
  build_cmd false
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" && "${cache_present_at_start}" == "true" ]] \
  && has_cache_snapshot \
  && scan_log_has_recoverable_nvd_failure; then
  echo "[security][dependency-check] NVD update 429/cache-corruption nedeniyle bozuldu; son saglam snapshot geri yuklenip cache-only modda yeniden deneniyor."
  NVD_API_KEY_VALUE=""
  restore_cache_snapshot
  build_cmd true
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -z "${NVD_API_KEY_VALUE}" ]] && grep -q "NoDataException" "${SCAN_LOG_PATH}"; then
  echo "[security][dependency-check] Cache-only denemesi veri tabani olusturamadi; anahtarsiz temiz bootstrap bir kez daha deneniyor."
  rm -rf "${LOCAL_DC_CACHE_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_DIR}"
  build_cmd false
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -z "${NVD_API_KEY_VALUE}" && "${cache_present_at_start}" != "true" ]] && has_cache_data; then
  echo "[security][dependency-check] Bootstrap sonrasi versioned cache olustu; cache-only modda bir kez daha deneniyor."
  build_cmd true
  scan_status=0
  run_scan
fi

for artifact in \
  "${TARGET_REPORT_DIR}/dependency-check-report.html" \
  "${TARGET_REPORT_DIR}/dependency-check-report.json" \
  "${TARGET_REPORT_DIR}/dependency-check-report.xml" \
  "${TARGET_REPORT_DIR}/dependency-check-report.csv"
do
  if [[ -f "${artifact}" ]]; then
    cp "${artifact}" "${REPORT_DIR}/"
  fi
done

echo "[security][dependency-check] Reports stored under ${REPORT_DIR}"
exit "${scan_status}"
