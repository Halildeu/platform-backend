# Backend deploy runtime AppRole policy.

path "secret/data/{{env}}/backend-deploy/config" {
  capabilities = ["read"]
}

path "secret/metadata/{{env}}/backend-deploy/*" {
  capabilities = ["list"]
}

path "secret/data/{{env}}/db/auth-service" {
  capabilities = ["read"]
}

path "secret/data/{{env}}/db/user-service" {
  capabilities = ["read"]
}

path "secret/data/{{env}}/db/permission-service" {
  capabilities = ["read"]
}

path "secret/data/{{env}}/db/variant-service" {
  capabilities = ["read"]
}

path "secret/metadata/{{env}}/db/*" {
  capabilities = ["list"]
}

path "secret/data/{{env}}/jwt/auth-service" {
  capabilities = ["read"]
}

path "secret/metadata/{{env}}/jwt/*" {
  capabilities = ["list"]
}
