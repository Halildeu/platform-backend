#!/usr/bin/env bash
set -euo pipefail

# Backend repo koruması: FE artefaktları yasak.
# Bu script, backend kökünden çağrılmalıdır.

ROOT_DIR=${1:-"."}
cd "$ROOT_DIR"

echo "[guard] Backend FE artefakt koruması başladı (pwd=$(pwd))"

violations=()

# 1) Tamamen yasak klasör: apps/ (FE MFE kopyaları)
if [[ -d "apps" ]]; then
  violations+=("apps/ dizini backend tarafında yasak (MFE klasörleri). Lütfen frontend repo'ya taşıyın.")
fi

# 2) Yasak config dosyaları (frontend/docs/examples hariç)
while IFS= read -r -d '' f; do
  # frontend/docs/examples altı hariç tutulur
  if [[ "$f" == frontend/docs/examples/* ]]; then
    continue
  fi
  violations+=("Bundler config tespit edildi: $f — FE artefaktları backend'de yasaktır.")
done < <(find . -type f \( \
  -name 'vite.config.*' -o \
  -name 'webpack.*.js' -o -name 'webpack.*.cjs' -o -name 'webpack.*.mjs' -o -name 'webpack.*.ts' -o \
  -name 'rollup.config.*' -o -name 'parcel.*.json' -o -name 'esbuild.*.js' -o -name 'next.config.*' \
\) -print0 || true)

# 3) Vite bağımlılığı (package.json içinde)
while IFS= read -r -d '' pj; do
  if rg -n '"vite"\s*:' -S "$pj" >/dev/null; then
    violations+=("$pj içinde 'vite' bağımlılığı tespit edildi — yasak.")
  fi
  if rg -n '@originjs/vite-plugin-federation' -S "$pj" >/dev/null; then
    violations+=("$pj içinde 'vite-plugin-federation' tespit edildi — yasak.")
  fi
done < <(find . -name package.json -type f -print0)

# 4) Module Federation izleri (docs hariç)
if rg -n "ModuleFederationPlugin|remoteEntry\\.js|exposes:\\s*\\{" -S -g '!**/node_modules/**' -g '!docs/**' . >/dev/null 2>&1; then
  violations+=("Module Federation ayak izi bulundu (kod/konfig). MF sadece frontend repo'da olmalı.")
fi

if (( ${#violations[@]} > 0 )); then
  echo "[guard] HATALAR:" >&2
  for v in "${violations[@]}"; do
    echo " - $v" >&2
  done
  exit 1
fi

echo "[guard] OK — Backend içinde FE artefaktı yok."
exit 0
