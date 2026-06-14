#!/usr/bin/env bash
set -uo pipefail
WORK=$(mktemp -d /tmp/tpmrepro.XXXXXX); cd "$WORK"
cleanup(){ [ -n "${SWPID:-}" ] && kill "$SWPID" 2>/dev/null || true; }
trap cleanup EXIT
mkdir -p st
swtpm_setup --tpm2 --tpmstate "$WORK/st" --overwrite >/dev/null 2>&1 || swtpm_setup --tpm2 --tpmstate "$WORK/st" --overwrite >/dev/null 2>&1
swtpm socket --tpm2 --tpmstate dir="$WORK/st" --ctrl type=tcp,port=2321 --server type=tcp,port=2320 --flags not-need-init,startup-clear --daemon &
SWPID=$!; sleep 1
export TPM2TOOLS_TCTI="swtpm:host=127.0.0.1,port=2320"
tpm2_startup -c 2>/dev/null || true
b64(){ base64 -w0 "$1" 2>/dev/null || echo ""; }
# EK
tpm2_createek -G rsa -c ek.ctx -u ek.pub >/dev/null
tpm2_flushcontext -t >/dev/null 2>&1 || true
tpm2_readpublic -c ek.ctx -f pem -o ek.pem >/dev/null
# Test manufacturer CA -> EK cert (verifier pins this root for tests)
openssl req -x509 -newkey rsa:3072 -keyout caKey.pem -out caCert.pem -days 3650 -nodes -subj "/CN=ACIK TEST TPM Manufacturer Root CA" >/dev/null 2>&1
openssl x509 -req -in <(openssl req -new -key caKey.pem -subj "/CN=ek" 2>/dev/null) -CA caCert.pem -CAkey caKey.pem -CAcreateserial -days 825 -force_pubkey ek.pem -out ekCert.pem >/dev/null 2>&1
openssl x509 -in ekCert.pem -outform der -out ek.crt.der 2>/dev/null
openssl x509 -in caCert.pem -outform der -out ca.crt.der 2>/dev/null
# AK
tpm2_flushcontext -t >/dev/null 2>&1 || true
tpm2_createak -C ek.ctx -c ak.ctx -G rsa -s rsassa -g sha256 -u ak.pub -n ak.name >/dev/null
AKNAME_HEX=$(xxd -p ak.name | tr -d "\n")
# MakeCredential + ActivateCredential
head -c 16 /dev/urandom > secret.bin
tpm2_makecredential -T none -e ek.pub -s secret.bin -n "$AKNAME_HEX" -o cred.out >/dev/null 2>&1
tpm2_flushcontext -t >/dev/null 2>&1 || true
tpm2_startauthsession --policy-session -S sess.ctx >/dev/null 2>&1
tpm2_policysecret -S sess.ctx -c e >/dev/null 2>&1
ACT=FAIL
tpm2_activatecredential -c ak.ctx -C ek.ctx -i cred.out -o recovered.bin -P"session:sess.ctx" >/dev/null 2>&1 && cmp -s secret.bin recovered.bin && ACT=MATCH
tpm2_flushcontext sess.ctx >/dev/null 2>&1 || true; tpm2_flushcontext -t >/dev/null 2>&1 || true
# Device key + certify by AK
tpm2_createprimary -C o -c primary.ctx >/dev/null 2>&1
tpm2_create -C primary.ctx -G rsa -u devkey.pub -r devkey.priv -c devkey.ctx >/dev/null 2>&1
tpm2_flushcontext -t >/dev/null 2>&1 || true
tpm2_certify -c devkey.ctx -C ak.ctx -g sha256 -o certify.attest -s certify.sig >/dev/null 2>&1 && CERT=ok || CERT=FAIL
tpm2_flushcontext -t >/dev/null 2>&1 || true
# Quote over nonce + PCR 0,7
NONCE_HEX=$(head -c 20 /dev/urandom | xxd -p | tr -d "\n")
tpm2_quote -c ak.ctx -l sha256:0,7 -q "$NONCE_HEX" -m quote.attest -s quote.sig -o pcr.bin >/dev/null 2>&1 && QUOTE=ok || QUOTE=FAIL
echo "STATUS activate=$ACT certify=$CERT quote=$QUOTE ekcert=$( [ -f ek.crt.der ] && echo ok || echo none)"
echo "=== GOLDEN_JSON_BEGIN ==="
printf "{\"schema\":\"faz22.3b.golden.v1\",\"activate\":\"%s\",\"nonceHex\":\"%s\",\"caCertDer\":\"%s\",\"ekCertDer\":\"%s\",\"ekPub\":\"%s\",\"akPub\":\"%s\",\"akNameHex\":\"%s\",\"credBlob\":\"%s\",\"activationExpectedB64\":\"%s\",\"certifyAttest\":\"%s\",\"certifySig\":\"%s\",\"devkeyPub\":\"%s\",\"quoteAttest\":\"%s\",\"quoteSig\":\"%s\"}\n" \
"$ACT" "$NONCE_HEX" "$(b64 ca.crt.der)" "$(b64 ek.crt.der)" "$(b64 ek.pub)" "$(b64 ak.pub)" "$AKNAME_HEX" "$(b64 cred.out)" "$(b64 secret.bin)" "$(b64 certify.attest)" "$(b64 certify.sig)" "$(b64 devkey.pub)" "$(b64 quote.attest)" "$(b64 quote.sig)"
echo "=== GOLDEN_JSON_END ==="
