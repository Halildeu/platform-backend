#!/usr/bin/env bash
# Faz 22.3B (ADR-0039) gate-4a-2.3 — REAL swtpm interop proof for the server-side software
# TPM2_MakeCredential (verifier V10). Proves our Java TpmMakeCredential is TPM-spec correct:
# a real TPM's tpm2_activatecredential (holding the EK private) recovers EXACTLY the secret our
# Java wrapped. This is the ground-truth interop gate (Codex 019ec723 required it alongside the
# Java make→activate self-round-trip unit test).
#
# Java is not assumed on the TPM host, so the flow is two-phase (the Java emit runs on the dev
# box via TpmMakeCredentialInteropEmitTest); EK+AK are persisted to NV handles so they survive
# between phases / swtpm restart.
#
# Usage (TPM host = where swtpm + tpm2-tools 5.2 live, e.g. staging-sw):
#   1) ./swtpm-makecredential-interop.sh setup
#        → prints EKPUB_B64 + AKNAME_HEX (EK→0x81010001, AK→0x81010002 persisted)
#   2) on the dev box, run the production MakeCredential over those inputs:
#        mvn -pl endpoint-admin-service -am -Dtest=TpmMakeCredentialInteropEmitTest \
#            -Dtpm.interop.emit=true -Dtpm.interop.ekpubB64=<EKPUB_B64> \
#            -Dtpm.interop.aknameHex=<AKNAME_HEX> -Dtpm.interop.secretHex=<16-byte-hex> \
#            -Dtpm.interop.outFile=cred.out -Dsurefire.failIfNoSpecifiedTests=false test
#      then copy cred.out to the TPM host as $WORK/cred.in
#   3) ./swtpm-makecredential-interop.sh activate
#        → prints RECOVERED_HEX ; assert it equals the secret hex from step 2 (== MATCH)
#   4) ./swtpm-makecredential-interop.sh cleanup
#
# PROVEN 2026-06-14 on staging-sw (swtpm 0.6.x + tpm2-tools 5.2): RSA-2048 EK, SHA-256;
# ACTIVATE=ok; RECOVERED_HEX == issued secret.
set -uo pipefail

WORK=${TPM_INTEROP_WORK:-/tmp/tpm-mkc-interop}
PORT=${TPM_INTEROP_PORT:-2322}
CTRL=$((PORT + 1))
EK_HANDLE=0x81010001
AK_HANDLE=0x81010002

start_swtpm() {
  pkill -f "port=$PORT" 2>/dev/null || true; sleep 0.4
  swtpm socket --tpm2 --tpmstate dir="$WORK/st" \
    --ctrl type=tcp,port=$CTRL --server type=tcp,port=$PORT \
    --flags not-need-init,startup-clear --daemon
  sleep 1
  export TPM2TOOLS_TCTI="swtpm:host=127.0.0.1,port=$PORT"
  tpm2_startup -c >/dev/null 2>&1 || true
}

case "${1:-}" in
  setup)
    rm -rf "$WORK"; mkdir -p "$WORK/st"; cd "$WORK"
    swtpm_setup --tpm2 --tpmstate "$WORK/st" --overwrite >/dev/null 2>&1
    start_swtpm
    tpm2_createek -G rsa -c ek.ctx -u ek.pub >/dev/null 2>&1
    tpm2_flushcontext -t >/dev/null 2>&1 || true
    tpm2_createak -C ek.ctx -c ak.ctx -G rsa -s rsassa -g sha256 -u ak.pub -n ak.name >/dev/null 2>&1
    tpm2_flushcontext -t >/dev/null 2>&1 || true
    tpm2_evictcontrol -c ek.ctx $EK_HANDLE >/dev/null 2>&1
    tpm2_flushcontext -t >/dev/null 2>&1 || true
    tpm2_evictcontrol -c ak.ctx $AK_HANDLE >/dev/null 2>&1
    pkill -f "port=$PORT" 2>/dev/null || true
    echo "EKPUB_B64=$(base64 -w0 ek.pub)"
    echo "AKNAME_HEX=$(xxd -p ak.name | tr -d '\n')"
    ;;
  activate)
    cd "$WORK"
    start_swtpm
    tpm2_startauthsession --policy-session -S sess.ctx >/dev/null 2>&1
    tpm2_policysecret -S sess.ctx -c e >/dev/null 2>&1   # EK endorsement auth
    if tpm2_activatecredential -c $AK_HANDLE -C $EK_HANDLE -i cred.in -o recovered.bin \
         -P"session:sess.ctx" >/dev/null 2>&1; then echo "ACTIVATE=ok"; else echo "ACTIVATE=FAIL"; fi
    tpm2_flushcontext sess.ctx >/dev/null 2>&1 || true
    echo "RECOVERED_HEX=$(xxd -p recovered.bin 2>/dev/null | tr -d '\n')"
    pkill -f "port=$PORT" 2>/dev/null || true
    ;;
  cleanup)
    pkill -f "port=$PORT" 2>/dev/null || true
    rm -rf "$WORK"
    echo "cleaned $WORK"
    ;;
  *)
    echo "usage: $0 {setup|activate|cleanup}" >&2; exit 2 ;;
esac
