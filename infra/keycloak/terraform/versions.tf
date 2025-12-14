terraform {
  required_version = ">= 1.5.0"
  required_providers {
    keycloak = {
      source  = "mrparkers/keycloak"
      version = ">= 4.1.0"
    }
  }
}

provider "keycloak" {
  url      = var.base_url
  username = var.username
  password = var.password
  client_id = "admin-cli"
}

