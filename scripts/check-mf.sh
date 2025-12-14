#!/usr/bin/env bash
set -euo pipefail

# MF paylaşımları ve Router kurallarını frontend/apps dizininde doğrular.
# Kullanım: ./scripts/check-mf.sh [FRONTEND_ROOT]
# Örn:     ./scripts/check-mf.sh ../frontend

FRONTEND_ROOT=${1:-"../frontend"}

echo "[check-mf] Frontend root: $FRONTEND_ROOT"
if [[ ! -d "$FRONTEND_ROOT/apps" ]]; then
  echo "[check-mf] HATA: $FRONTEND_ROOT/apps bulunamadı" >&2
  exit 2
fi

APPS=(mfe-shell mfe-users mfe-access mfe-reporting mfe-audit mfe-suggestions mfe-ethic)
REQ_SHARED=(react react-dom react-router react-router-dom)

pass=0; fail=0

check_shared() {
  local app=$1
  local cfgs
  IFS=$'\n' read -r -d '' -a cfgs < <(ls "$FRONTEND_ROOT/apps/$app"/webpack*.js 2>/dev/null && printf '\0') || true
  if [[ ${#cfgs[@]} -eq 0 ]]; then
    echo "[WARN] $app: webpack*.js bulunamadı (skip)"
    return 0
  fi
  local ok=1
  for cfg in "${cfgs[@]}"; do
    for dep in "${REQ_SHARED[@]}"; do
      if ! rg -n "shared:\s*\{[\s\S]*['\"]$dep['\"]\s*:\s*\{[\s\S]*singleton\s*:\s*true" -S "$cfg" >/dev/null; then
        echo "[FAIL] $app: $cfg içinde $dep singleton:true yok"
        ok=0
      fi
    done
  done
  if [[ $ok -eq 1 ]]; then
    echo "[OK]   $app: shared blokları (react/react-dom/react-router/react-router-dom)"
    ((pass++))
  else
    ((fail++))
  fi
}

check_router_wrapping() {
  local app=$1
  local src="$FRONTEND_ROOT/apps/$app/src"
  [[ -d "$src" ]] || return 0
  if rg -n "BrowserRouter|MemoryRouter" -S "$src" >/dev/null; then
    echo "[FAIL] $app: Remote içinde Router sarması tespit edildi (BrowserRouter/MemoryRouter)"
    ((fail++))
  else
    echo "[OK]   $app: Remote içinde Router sarması yok"
    ((pass++))
  fi
}

echo "[check-mf] Shared ve Router kontrolleri başlıyor..."
for app in "${APPS[@]}"; do
  if [[ -d "$FRONTEND_ROOT/apps/$app" ]]; then
    check_shared "$app"
    if [[ "$app" != "mfe-shell" ]]; then
      check_router_wrapping "$app"
    fi
  else
    echo "[SKIP] $app: dizin yok"
  fi
done

# Shell remotes basit kontrol (dev config)
SHELL_DEV="$FRONTEND_ROOT/apps/mfe-shell/webpack.dev.js"
if [[ -f "$SHELL_DEV" ]]; then
  if rg -n "remotes:\s*\{" -S "$SHELL_DEV" >/dev/null; then
    echo "[OK]   mfe-shell: remotes tanımlı (dev)"
    ((pass++))
  else
    echo "[FAIL] mfe-shell: remotes dev config'te bulunamadı"
    ((fail++))
  fi
else
  echo "[SKIP] mfe-shell: webpack.dev.js yok"
fi

echo "[check-mf] Tamamlandı. PASS=$pass FAIL=$fail"
if [[ $fail -ne 0 ]]; then
  exit 1
fi
exit 0

