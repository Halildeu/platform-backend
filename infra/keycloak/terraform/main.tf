resource "keycloak_realm" "platform" {
  realm   = var.realm
  enabled = true
}

# Audience client scopes
resource "keycloak_openid_client_scope" "aud_user_service" {
  realm_id = keycloak_realm.platform.id
  name     = "aud-user-service"
  protocol = "openid-connect"
}

resource "keycloak_openid_audience_protocol_mapper" "aud_user_service_mapper" {
  realm_id                = keycloak_realm.platform.id
  client_scope_id         = keycloak_openid_client_scope.aud_user_service.id
  name                    = "aud-user-service"
  included_custom_audience = "user-service"
  add_to_access_token     = true
  add_to_id_token         = false
}

resource "keycloak_openid_client_scope" "aud_permission_service" {
  realm_id = keycloak_realm.platform.id
  name     = "aud-permission-service"
  protocol = "openid-connect"
}

resource "keycloak_openid_audience_protocol_mapper" "aud_permission_service_mapper" {
  realm_id                = keycloak_realm.platform.id
  client_scope_id         = keycloak_openid_client_scope.aud_permission_service.id
  name                    = "aud-permission-service"
  included_custom_audience = "permission-service"
  add_to_access_token     = true
  add_to_id_token         = false
}

locals {
  default_scopes = [
    keycloak_openid_client_scope.aud_user_service.id,
    keycloak_openid_client_scope.aud_permission_service.id,
  ]
}

resource "keycloak_openid_client" "svc" {
  for_each                 = toset(var.service_clients)
  realm_id                 = keycloak_realm.platform.id
  client_id                = each.value
  name                     = each.value
  enabled                  = true
  access_type              = "CONFIDENTIAL"
  service_accounts_enabled = true
  standard_flow_enabled    = false
  direct_access_grants_enabled = false
  valid_redirect_uris      = ["*"]
  default_client_scopes    = local.default_scopes
}

output "client_secrets" {
  value = { for k, v in keycloak_openid_client.svc : k => v.client_secret }
  sensitive = true
}

