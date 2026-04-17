# Vault auto-unseal with Google Cloud KMS — P1.10 (Codex thread 019d9b8d).
#
# Activation: mount this file alongside base vault.hcl in prod only, via
#   docker-compose.prod.yml VAULT_SEAL_FILE=./devops/vault/vault-seal-gcpckms.hcl
# Local/dev keep Shamir (no seal stanza → base vault.hcl alone).
#
# Configuration is provided via environment variables read directly by Vault
# (HCL string-level `${VAR}` interpolation is NOT generally supported in seal
# stanzas — avoid it; use the documented env variables instead).
#
# Required environment variables (set in deploy env, passed into the Vault
# container — names follow HashiCorp docs, not Vault-prefixed invention):
#   GOOGLE_PROJECT                  — e.g. "erp-platform-prod"
#   GOOGLE_REGION                   — e.g. "global" or "europe-west1"
#   VAULT_GCPCKMS_SEAL_KEY_RING     — key ring name
#   VAULT_GCPCKMS_SEAL_CRYPTO_KEY   — symmetric crypto key name
#
# GCP credential resolution (Application Default Credentials):
#   1. GKE Workload Identity (preferred — no key file; metadata server used)
#   2. GOOGLE_APPLICATION_CREDENTIALS pointing at a mounted SA key (fallback)
#
# IAM role required for the SA:
#   roles/cloudkms.cryptoKeyEncrypterDecrypter — scoped to the specific key.
#
# Fresh-init flow, break-glass, and the full env table are in
#   docs/04-operations/RUNBOOKS/RB-vault-kms-autounseal.md
#
# HashiCorp docs: https://developer.hashicorp.com/vault/docs/configuration/seal/gcpckms

seal "gcpckms" {}
