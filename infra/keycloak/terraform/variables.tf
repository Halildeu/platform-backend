variable "base_url" { type = string }
variable "username" { type = string }
variable "password" { type = string }
variable "realm" { type = string default = "platform" }
variable "service_clients" {
  type    = list(string)
  default = ["gateway", "user-service", "permission-service", "variant-service"]
}

