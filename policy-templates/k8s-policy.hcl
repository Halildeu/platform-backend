# Kubernetes auth politika şablonu. `{env}`, `{service}`, `{namespace}` değerlerini düzenleyin.

path "secret/data/{env}/{service}/*" {
  capabilities = ["read"]
}

path "secret/metadata/{env}/{service}/*" {
  capabilities = ["list"]
}

path "sys/mounts" {
  capabilities = ["list"]
}

path "auth/token/lookup-self" {
  capabilities = ["read"]
}
