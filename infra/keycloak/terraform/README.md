Terraform ile Keycloak Provisioning (Örnek)

Amaç
- Realm, client scopes (audience) ve service client’ları kodla yönetmek.

Kullanım (özet)
```
cd infra/keycloak/terraform
cp terraform.tfvars.example terraform.tfvars
terraform init
terraform apply
```

Değişkenler
- `base_url`: Keycloak adresi (örn. https://keycloak-stage.example.com)
- `username` / `password`: Admin kullanıcı
- `realm`: platform (varsayılan)
- `service_clients`: ["gateway","user-service","permission-service","variant-service"]

Notlar
- Üretilen client secret’ları dışarıya çıkarmak yerine CI secret store/Vault yazımı tercih edin.
- Bu örnek, demoya yöneliktir; prod’da state backend ve credentials yönetimini güvenceye alın.

