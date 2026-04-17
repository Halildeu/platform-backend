# Vault auto-unseal via an external Vault Transit engine — P1.10.
#
# Use case: bootstrapping the first prod Vault when no Cloud KMS is available
# (or when the operator prefers a self-hosted HSM-backed keystore). The external
# "transit" Vault must already be unsealed; typically it runs on a separate
# host with Shamir + operator-held keys, unsealed only when this prod Vault
# needs to start.
#
# Activation: mount this file alongside base vault.hcl in prod only, via
#   docker-compose.prod.yml VAULT_SEAL_FILE=./devops/vault/vault-seal-transit.hcl
# Local/dev keep Shamir (no seal stanza → base vault.hcl alone).
#
# Configuration is provided via environment variables read directly by Vault
# (HCL string-level `${VAR}` interpolation is NOT generally supported in seal
# stanzas — avoid it; use the documented env variables instead).
#
# Required environment variables (set in deploy env, passed into the Vault
# container — names follow HashiCorp docs):
#   VAULT_ADDR                      — e.g. "https://vault-kms.internal:8200"
#                                     (transit Vault endpoint)
#   VAULT_TOKEN                     — periodic token with transit/encrypt + decrypt
#   VAULT_TRANSIT_SEAL_MOUNT_PATH   — e.g. "transit/" (trailing slash required)
#   VAULT_TRANSIT_SEAL_KEY_NAME     — e.g. "autounseal-prod"
#
# Optional:
#   VAULT_SKIP_VERIFY               — "true" to skip TLS verify (dev only)
#   VAULT_TRANSIT_SEAL_NAMESPACE    — Vault Enterprise namespace, if applicable
#
# NOTE: VAULT_ADDR / VAULT_TOKEN shadow the CLI defaults; if this Vault needs
# to talk to *itself* via CLI inside the container (e.g. healthchecks), set
# them via vault CLI flags (-address, -token) rather than relying on env.
#
# Transit Vault side (one-time setup, documented in RB-vault-kms-autounseal.md):
#   vault secrets enable transit
#   vault write -f transit/keys/autounseal-prod
#   vault policy write autounseal-prod - <<EOF
#     path "transit/encrypt/autounseal-prod" { capabilities = ["update"] }
#     path "transit/decrypt/autounseal-prod" { capabilities = ["update"] }
#   EOF
#   vault token create -policy=autounseal-prod -period=720h
#
# Fresh-init flow, break-glass, and the full env table are in
#   docs/04-operations/RUNBOOKS/RB-vault-kms-autounseal.md
#
# HashiCorp docs: https://developer.hashicorp.com/vault/docs/configuration/seal/transit

seal "transit" {}
