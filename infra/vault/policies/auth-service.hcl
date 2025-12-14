# Auth Service runtime AppRole policy.
# Replace {{env}} with stage/prod before uploading to Vault.

path "secret/data/{{env}}/auth-service" {
  capabilities = ["read"]
}

path "secret/data/{{env}}/auth-service/*" {
  capabilities = ["read"]
}

path "secret/metadata/{{env}}/auth-service" {
  capabilities = ["read", "list"]
}

path "secret/metadata/{{env}}/auth-service/*" {
  capabilities = ["read", "list"]
}

# Allow the service to list the parent env namespace (needed for fail-fast diagnostics).
path "secret/metadata/{{env}}" {
  capabilities = ["list"]
}
