# CI / rotation policy for updating auth-service secrets.
# Replace {{env}} with stage/prod before uploading.

path "secret/data/{{env}}/auth-service" {
  capabilities = ["create", "update", "read"]
}

path "secret/data/{{env}}/auth-service/*" {
  capabilities = ["create", "update", "read"]
}

path "secret/metadata/{{env}}/auth-service" {
  capabilities = ["read", "list", "delete"]
}

path "secret/metadata/{{env}}/auth-service/*" {
  capabilities = ["read", "list", "delete"]
}
