#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
BACKEND_POM="${ROOT_DIR}/pom.xml"
REPORT_DIR="${ROOT_DIR}/test-results/security/sbom"
mkdir -p "${REPORT_DIR}"
cd "${ROOT_DIR}"

CYCLONEDX_VERSION="${CYCLONEDX_VERSION:-2.7.9}"
SBOM_FORMAT="${SBOM_FORMAT:-json}"

echo "[security][sbom] Generating CycloneDX SBOM (${SBOM_FORMAT})"
mvn -B \
  -f "${BACKEND_POM}" \
  org.cyclonedx:cyclonedx-maven-plugin:"${CYCLONEDX_VERSION}":makeAggregateBom \
  -DskipTests=true \
  -Dcyclonedx.skipAttach=true \
  -Dcyclonedx.includeBomSerialNumber=true \
  -Dcyclonedx.outputFormat="${SBOM_FORMAT}" \
  -Dcyclonedx.outputName="bom" \
  -Dcyclonedx.outputDirectory="${REPORT_DIR}"

SBOM_PATH="${REPORT_DIR}/bom.${SBOM_FORMAT}"
GENERATED_SBOM_PATH="${ROOT_DIR}/target/bom.${SBOM_FORMAT}"

if [[ -f "${GENERATED_SBOM_PATH}" && "${GENERATED_SBOM_PATH}" != "${SBOM_PATH}" ]]; then
  cp "${GENERATED_SBOM_PATH}" "${SBOM_PATH}"
fi

if [[ ! -f "${SBOM_PATH}" ]]; then
  echo "[security][sbom] HATA: SBOM bulunamadi: ${SBOM_PATH}" >&2
  exit 1
fi

echo "[security][sbom] SBOM written to ${SBOM_PATH}"

if [[ -n "${COSIGN_PRIVATE_KEY:-}" ]]; then
  COSIGN_ARGS=(
    sign-blob
    "--key" "env://COSIGN_PRIVATE_KEY"
  )
  COSIGN_HELP="$(cosign sign-blob --help 2>/dev/null || true)"
  if grep -q -- "--new-bundle-format" <<<"${COSIGN_HELP}"; then
    COSIGN_ARGS+=("--new-bundle-format=false")
  fi
  if grep -q -- "--use-signing-config" <<<"${COSIGN_HELP}"; then
    COSIGN_ARGS+=("--use-signing-config=false")
  fi
  if [[ "${CI:-false}" == "true" || "${COSIGN_NONINTERACTIVE:-true}" == "true" ]]; then
    COSIGN_ARGS+=("--yes")
  fi
  if [[ -n "${COSIGN_PASSWORD:-}" ]]; then
    export COSIGN_PASSWORD
  fi

  echo "[security][sbom] Signing SBOM with cosign"
  cosign "${COSIGN_ARGS[@]}" \
    --output-signature "${SBOM_PATH}.sig" \
    --output-certificate "${SBOM_PATH}.cert" \
    "${SBOM_PATH}"
  echo "[security][sbom] Signature stored at ${SBOM_PATH}.sig"
else
  if [[ "${COSIGN_REQUIRED:-false}" == "true" ]]; then
    echo "::warning::COSIGN_PRIVATE_KEY not provided; SBOM signature skipped."
  else
    echo "::notice::COSIGN_PRIVATE_KEY not provided; SBOM signature skipped."
  fi
fi
