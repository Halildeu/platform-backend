# AppRole politika şablonu. `{env}` ve `{service}` alanlarını güncelleyin.

path "secret/data/{env}/{service}/*" {
  capabilities = ["read", "list"]
}

path "secret/metadata/{env}/{service}/*" {
  capabilities = ["list"]
}

# Rotasyon pipeline’ı için yazma yetkisi örneği
path "secret/data/{env}/{service}/rotation" {
  capabilities = ["create", "update"]
}
