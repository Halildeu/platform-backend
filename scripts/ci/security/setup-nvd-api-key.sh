#!/usr/bin/env bash
# Helper script to export the NVD API key locally before running dependency scans.

if [[ -z "${1:-}" ]]; then
  echo "Usage: ./scripts/ci/security/setup-nvd-api-key.sh <6a6e9b16-5c10-43f7-9ba6-018d2dcadc2e>"
  exit 1
fi

export NVD_API_KEY="$1"
echo "NVD_API_KEY exported for current shell session."
echo "Next step: run './scripts/ci/security/run-dependency-scan.sh'."
