ui = true
disable_mlock = true

listener "tcp" {
  address       = "0.0.0.0:8200"
  tls_cert_file = "/vault/tls/tls.crt"
  tls_key_file  = "/vault/tls/tls.key"
}

api_addr     = "https://127.0.0.1:8200"
cluster_addr = "https://127.0.0.1:8201"

storage "raft" {
  path    = "/vault/file"
  node_id = "prod-raft-1"
}

telemetry {
  prometheus_retention_time = "24h"
  disable_hostname         = true
}
