#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
REPORT_DIR="${ROOT_DIR}/test-results/security/sbom"
mkdir -p "${REPORT_DIR}"

CYCLONEDX_VERSION="${CYCLONEDX_VERSION:-2.7.9}"
SBOM_FORMAT="${SBOM_FORMAT:-json}"

echo "[security][sbom] Generating CycloneDX SBOM (${SBOM_FORMAT})"
mvn -B \
  org.cyclonedx:cyclonedx-maven-plugin:"${CYCLONEDX_VERSION}":makeAggregateBom \
  -DskipTests=true \
  -Dcyclonedx.skipAttach=true \
  -Dcyclonedx.includeBomSerialNumber=true \
  -Dcyclonedx.outputFormat="${SBOM_FORMAT}" \
  -Dcyclonedx.outputName="bom" \
  -Dcyclonedx.outputDirectory="${REPORT_DIR}"

SBOM_PATH="${REPORT_DIR}/bom.${SBOM_FORMAT}"
echo "[security][sbom] SBOM written to ${SBOM_PATH}"

if [[ -n "${COSIGN_PRIVATE_KEY:-}" ]]; then
  COSIGN_ARGS=(sign-blob "--key" "env://COSIGN_PRIVATE_KEY")
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
  echo "::warning::COSIGN_PRIVATE_KEY not provided; SBOM signature skipped."
fi
