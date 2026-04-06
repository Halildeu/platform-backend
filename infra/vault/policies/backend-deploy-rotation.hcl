# Backend deploy rotation/admin AppRole policy.

path "secret/data/{{env}}/backend-deploy/config" {
  capabilities = ["create", "update", "read"]
}

path "secret/metadata/{{env}}/backend-deploy/*" {
  capabilities = ["list"]
}

path "secret/data/{{env}}/ops/github/backend-deploy" {
  capabilities = ["create", "update", "read"]
}

path "secret/metadata/{{env}}/ops/github/*" {
  capabilities = ["list"]
}
