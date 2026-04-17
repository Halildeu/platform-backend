# Vault auto-unseal with AWS KMS — P1.10 (Codex thread 019d9b8d).
#
# Activation: mount this file alongside base vault.hcl in prod only, via
#   docker-compose.prod.yml VAULT_SEAL_FILE=./devops/vault/vault-seal-awskms.hcl
# Local/dev keep Shamir (no seal stanza → base vault.hcl alone).
#
# Configuration is provided via environment variables read directly by Vault
# (HCL string-level `${VAR}` interpolation is NOT generally supported in seal
# stanzas — avoid it; use the documented env variables instead).
#
# Required environment variables (set in deploy env, passed into the Vault
# container):
#   VAULT_AWSKMS_SEAL_KEY_ID   — KMS key ID or alias (e.g. alias/vault-unseal-prod)
#   AWS_REGION                 — e.g. "eu-central-1"
#
# AWS credential resolution (use the first available in this order):
#   1. EC2 instance profile / ECS task role (preferred — no static secrets)
#   2. AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY env vars (fallback)
#
# IAM policy required (minimum, scoped to the key ARN):
#   kms:Encrypt, kms:Decrypt, kms:DescribeKey
#
# Fresh-init flow, break-glass, and the full env table are in
#   docs/04-operations/RUNBOOKS/RB-vault-kms-autounseal.md
#
# HashiCorp docs: https://developer.hashicorp.com/vault/docs/configuration/seal/awskms

seal "awskms" {}
