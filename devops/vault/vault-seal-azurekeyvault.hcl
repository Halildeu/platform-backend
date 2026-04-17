# Vault auto-unseal with Azure Key Vault — P1.10 (Codex thread 019d9b8d).
#
# Activation: mount this file alongside base vault.hcl in prod only, via
#   docker-compose.prod.yml VAULT_SEAL_FILE=./devops/vault/vault-seal-azurekeyvault.hcl
# Local/dev keep Shamir (no seal stanza → base vault.hcl alone).
#
# Configuration is provided via environment variables read directly by Vault
# (HCL string-level `${VAR}` interpolation is NOT generally supported in seal
# stanzas — avoid it; use the documented env variables instead).
#
# Required environment variables (set in deploy env, passed into the Vault
# container — names follow HashiCorp docs):
#   AZURE_TENANT_ID                  — Azure AD tenant
#   VAULT_AZUREKEYVAULT_VAULT_NAME   — Key Vault name (without vault.azure.net suffix)
#   VAULT_AZUREKEYVAULT_KEY_NAME     — key name inside the Key Vault
#
# Azure credential resolution:
#   1. Managed Identity (preferred — VM/AKS Pod; IMDS used automatically when
#      AZURE_CLIENT_ID and AZURE_CLIENT_SECRET are not set)
#   2. Service Principal (fallback) via:
#        AZURE_CLIENT_ID
#        AZURE_CLIENT_SECRET
#
# Azure RBAC required on the Key Vault key:
#   Key Vault Crypto User role (or unwrapKey + wrapKey + get via Access Policy).
#
# Fresh-init flow, break-glass, and the full env table are in
#   docs/04-operations/RUNBOOKS/RB-vault-kms-autounseal.md
#
# HashiCorp docs: https://developer.hashicorp.com/vault/docs/configuration/seal/azurekeyvault

seal "azurekeyvault" {}
