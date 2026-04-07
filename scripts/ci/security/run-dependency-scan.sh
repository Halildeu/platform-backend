#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND_POM="${ROOT_DIR}/pom.xml"
MVN_CMD="${ROOT_DIR}/mvnw"
REPORT_DIR="${ROOT_DIR}/test-results/security/dependency-check"
TARGET_REPORT_DIR="${ROOT_DIR}/target"
LOCAL_ENV_LOADER="${ROOT_DIR}/../scripts/ops/load_local_env.sh"
mkdir -p "${REPORT_DIR}"
cd "${ROOT_DIR}"

if [[ -f "${LOCAL_ENV_LOADER}" ]]; then
  # shellcheck source=/dev/null
  source "${LOCAL_ENV_LOADER}" \
    NVD_API_KEY \
    DEPENDENCY_CHECK_VERSION \
    DEPENDENCY_CHECK_FAIL_CVSS \
    DEPENDENCY_CHECK_MODE \
    DEPENDENCY_CHECK_ALLOW_BOOTSTRAP_ON_CACHE_MISS \
    DEPENDENCY_CHECK_NVD_API_DELAY_MS \
    DEPENDENCY_CHECK_NVD_MAX_RETRY_COUNT \
    DEPENDENCY_CHECK_NVD_RESULTS_PER_PAGE
fi

DC_VERSION="${DEPENDENCY_CHECK_VERSION:-12.2.0}"
FAIL_CVSS="${DEPENDENCY_CHECK_FAIL_CVSS:-7.0}"
REQUESTED_MODE="${DEPENDENCY_CHECK_MODE:-full}"
ALLOW_BOOTSTRAP_ON_CACHE_MISS="${DEPENDENCY_CHECK_ALLOW_BOOTSTRAP_ON_CACHE_MISS:-true}"
NVD_API_KEY_VALUE="${NVD_API_KEY:-}"
NVD_API_DELAY_MS="${DEPENDENCY_CHECK_NVD_API_DELAY_MS:-5000}"
NVD_API_MAX_RETRY_COUNT="${DEPENDENCY_CHECK_NVD_MAX_RETRY_COUNT:-40}"
NVD_API_RESULTS_PER_PAGE="${DEPENDENCY_CHECK_NVD_RESULTS_PER_PAGE:-2000}"
CACHE_DIR_SUFFIX="${DC_VERSION//./_}"
LOCAL_DC_CACHE_DIR="${REPORT_DIR}/cache-v${CACHE_DIR_SUFFIX}"
LOCAL_DC_CACHE_SNAPSHOT_DIR="${REPORT_DIR}/cache-snapshot-v${CACHE_DIR_SUFFIX}"
SUPPRESSION_FILE="${ROOT_DIR}/scripts/ci/security/dependency-check-suppressions.xml"
SCAN_LOG_PATH="${REPORT_DIR}/dependency-check.log"

normalize_bool() {
  local lowered
  lowered="$(printf '%s' "${1}" | tr '[:upper:]' '[:lower:]')"
  case "${lowered}" in
    1|true|yes|on) echo "true" ;;
    0|false|no|off) echo "false" ;;
    *) echo "false" ;;
  esac
}

ALLOW_BOOTSTRAP_ON_CACHE_MISS="$(normalize_bool "${ALLOW_BOOTSTRAP_ON_CACHE_MISS}")"

case "${REQUESTED_MODE}" in
  full|cache-only)
    ;;
  *)
    echo "[security][dependency-check] Gecersiz DEPENDENCY_CHECK_MODE=${REQUESTED_MODE}. Beklenen: full | cache-only" >&2
    exit 2
    ;;
esac

echo "[security][dependency-check] Running OWASP Dependency-Check v${DC_VERSION} (fail on CVSS >= ${FAIL_CVSS}, mode=${REQUESTED_MODE})"

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
  local requested_mode="$1"
  local use_cached_data="$2"

  cmd=(
    "${MVN_CMD}" -B
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

  if [[ "${requested_mode}" == "cache-only" && "${use_cached_data}" == "true" ]]; then
    echo "[security][dependency-check] Cache-only mod: mevcut versioned cache ile auto update kapatiliyor."
    rm -f "${LOCAL_DC_CACHE_DIR}/odc.update.lock"
    cmd+=("-DautoUpdate=false")
    cmd+=("-DossindexAnalyzerEnabled=false")
    return
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

initial_mode="${REQUESTED_MODE}"
if [[ "${REQUESTED_MODE}" == "cache-only" && "${cache_present_at_start}" != "true" ]]; then
  if [[ "${ALLOW_BOOTSTRAP_ON_CACHE_MISS}" == "true" ]]; then
    echo "[security][dependency-check] Cache-only mod istendi ancak versioned cache bulunamadi; tek seferlik bootstrap ile devam ediliyor."
    initial_mode="full"
  else
    echo "[security][dependency-check] Cache-only mod istendi ancak versioned cache yok ve bootstrap kapali. Tarama fail-closed durduruluyor." >&2
    exit 3
  fi
fi

if [[ "${cache_present_at_start}" == "true" ]]; then
  prepare_cache_snapshot
fi

build_cmd "${initial_mode}" "${cache_present_at_start}"

scan_status=0
: > "${SCAN_LOG_PATH}"
run_scan

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" ]] && grep -q "Invalid API Key" "${SCAN_LOG_PATH}" && has_cache_data; then
  echo "[security][dependency-check] Gecersiz NVD_API_KEY algilandi; versioned cache ile cache-only modda yeniden deneniyor."
  NVD_API_KEY_VALUE=""
  build_cmd "cache-only" true
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" ]] && grep -q "Invalid API Key" "${SCAN_LOG_PATH}"; then
  echo "[security][dependency-check] Gecersiz NVD_API_KEY reusable cache olmadan geldi; anahtarsiz bootstrap icin versioned cache sifirlaniyor."
  NVD_API_KEY_VALUE=""
  rm -rf "${LOCAL_DC_CACHE_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_DIR}"
  build_cmd "full" false
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -n "${NVD_API_KEY_VALUE}" && "${cache_present_at_start}" == "true" ]] \
  && has_cache_snapshot \
  && scan_log_has_recoverable_nvd_failure; then
  echo "[security][dependency-check] NVD update 429/cache-corruption nedeniyle bozuldu; son saglam snapshot geri yuklenip cache-only modda yeniden deneniyor."
  NVD_API_KEY_VALUE=""
  restore_cache_snapshot
  build_cmd "cache-only" true
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -z "${NVD_API_KEY_VALUE}" ]] && grep -q "NoDataException" "${SCAN_LOG_PATH}"; then
  echo "[security][dependency-check] Cache-only denemesi veri tabani olusturamadi; anahtarsiz temiz bootstrap bir kez daha deneniyor."
  rm -rf "${LOCAL_DC_CACHE_DIR}"
  mkdir -p "${LOCAL_DC_CACHE_DIR}"
  build_cmd "full" false
  scan_status=0
  run_scan
fi

if [[ "${scan_status}" -ne 0 && -z "${NVD_API_KEY_VALUE}" && "${cache_present_at_start}" != "true" ]] && has_cache_data; then
  echo "[security][dependency-check] Bootstrap sonrasi versioned cache olustu; cache-only modda bir kez daha deneniyor."
  build_cmd "cache-only" true
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
