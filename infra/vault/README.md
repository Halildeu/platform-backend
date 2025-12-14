# Vault Policies & AppRole Notes

Bu klasör Vault tarafında stage/prod ortamları için uygulayacağımız politika dosyalarını ve referans yapılarını içerir.

- `policies/auth-service.hcl`: Auth Service runtime AppRole’u için yalnız `secret/<env>/auth-service` path’lerine okuma/listeme yetkisi verir.
- `policies/auth-service-rotation.hcl`: CI/rotasyon job’u için aynı path’lerde create/update hakkı tanımlar (servisler bu policy’yi kullanmaz).

Policy dosyalarındaki `{{env}}` placeholder’ını `envsubst` veya Terraform değişkenleri ile (`stage`, `prod`) değiştirip `vault policy write` komutuna verin. Ayrıntılar için `docs/04-operations/01-runbooks/vault-approle-auth-service.md` dokümanına bakın. 
