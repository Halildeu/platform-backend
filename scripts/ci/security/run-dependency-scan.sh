#!/usr/bin/env bash
set -euo pipefail

# =============================================================================
# Dependency vulnerability scan using OSV-Scanner
# Replaces OWASP Dependency-Check (NVD) which took 15-40 minutes.
# OSV-Scanner completes in ~15 seconds using the OSV database (24 sources
# including NVD, GitHub Advisory, Go, Rust, PyPI, etc.)
# =============================================================================

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
WEB_DIR="$(cd "${ROOT_DIR}/.." && pwd)/web"
REPORT_DIR="${ROOT_DIR}/test-results/security/dependency-check"
mkdir -p "${REPORT_DIR}"

echo "[security][osv-scanner] Starting dependency vulnerability scan"

# Install osv-scanner if not available (pre-built binary, ~3s)
if ! command -v osv-scanner &>/dev/null; then
  echo "[security][osv-scanner] Installing osv-scanner (pre-built binary)..."
  OSV_VERSION="2.0.2"
  case "$(uname -s)-$(uname -m)" in
    Darwin-arm64) OSV_ASSET="osv-scanner_darwin_arm64" ;;
    Darwin-x86_64) OSV_ASSET="osv-scanner_darwin_amd64" ;;
    Linux-x86_64) OSV_ASSET="osv-scanner_linux_amd64" ;;
    Linux-aarch64) OSV_ASSET="osv-scanner_linux_arm64" ;;
    *) echo "[security][osv-scanner] Unsupported platform: $(uname -s)-$(uname -m), skipping"; OSV_ASSET="" ;;
  esac
  if [[ -n "${OSV_ASSET}" ]]; then
    OSV_INSTALL_DIR="${HOME}/.local/bin"
    mkdir -p "${OSV_INSTALL_DIR}"
    curl -sSfL "https://github.com/google/osv-scanner/releases/download/v${OSV_VERSION}/${OSV_ASSET}" -o "${OSV_INSTALL_DIR}/osv-scanner"
    chmod +x "${OSV_INSTALL_DIR}/osv-scanner"
    export PATH="${OSV_INSTALL_DIR}:${PATH}"
  fi
fi

scan_exit=0

# Scan backend (Maven)
echo "[security][osv-scanner] Scanning backend dependencies (pom.xml)..."
if [[ -f "${ROOT_DIR}/pom.xml" ]]; then
  osv-scanner --lockfile="${ROOT_DIR}/pom.xml" \
    --format json \
    --output "${REPORT_DIR}/osv-backend-report.json" 2>&1 || scan_exit=$?
  echo "[security][osv-scanner] Backend scan exit code: ${scan_exit}"
fi

# Scan frontend (pnpm)
echo "[security][osv-scanner] Scanning web dependencies (pnpm-lock.yaml)..."
if [[ -f "${WEB_DIR}/pnpm-lock.yaml" ]]; then
  osv-scanner --lockfile="${WEB_DIR}/pnpm-lock.yaml" \
    --format json \
    --output "${REPORT_DIR}/osv-web-report.json" 2>&1 || {
    web_exit=$?
    if [[ "${web_exit}" -gt "${scan_exit}" ]]; then
      scan_exit="${web_exit}"
    fi
  }
  echo "[security][osv-scanner] Web scan exit code: ${scan_exit}"
fi

echo "[security][osv-scanner] Reports stored under ${REPORT_DIR}"
echo "[security][osv-scanner] Scan completed (exit=${scan_exit})"

# OSV exit codes: 0=clean, 1=vulnerabilities found, 2+=error
# For now, report but don't fail the build (gradual rollout)
# TODO: Change to `exit "${scan_exit}"` when ready to enforce
exit 0
