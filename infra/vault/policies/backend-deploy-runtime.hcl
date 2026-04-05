# Backend deploy runtime AppRole policy.

path "secret/data/{{env}}/backend-deploy/config" {
  capabilities = ["read"]
}

path "secret/metadata/{{env}}/backend-deploy/*" {
  capabilities = ["list"]
}
