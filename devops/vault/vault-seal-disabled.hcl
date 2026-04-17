# Placeholder for Shamir mode — no seal stanza.
#
# This file is mounted as /vault/config/active-seal.hcl when VAULT_SEAL_FILE
# is not overridden. Vault merges all *.hcl files in /vault/config when started
# with `-config=/vault/config/`, so an empty stanza keeps Shamir (default).
#
# Prod auto-unseal: set VAULT_SEAL_FILE in deploy/docker-compose.prod.yml to
# one of:
#   ./devops/vault/vault-seal-awskms.hcl
#   ./devops/vault/vault-seal-gcpckms.hcl
#   ./devops/vault/vault-seal-azurekeyvault.hcl
#   ./devops/vault/vault-seal-transit.hcl
#
# See docs/04-operations/RUNBOOKS/RB-vault.md for fresh-init and break-glass.
