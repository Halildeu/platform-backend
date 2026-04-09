ui = true
disable_mlock = true

listener "tcp" {
  address     = "0.0.0.0:8200"
  tls_disable = 1
  # TODO Faz 2: TLS aktif et
  # tls_cert_file = "/vault/tls/tls.crt"
  # tls_key_file  = "/vault/tls/tls.key"
  # tls_disable   = 0
}

api_addr     = "http://127.0.0.1:8200"
cluster_addr = "http://127.0.0.1:8201"

storage "raft" {
  path    = "/vault/file"
  node_id = "prod-raft-1"
}

telemetry {
  prometheus_retention_time = "24h"
  disable_hostname         = true
}
