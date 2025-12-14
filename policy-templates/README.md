# Policy Template Katmanı

Faz 1 gereği her servis için AppRole veya Kubernetes auth üzerinden Vault politikalarının standartlaştırılması. Bu klasör, temel şablonları ve CI kontrollerine dair notları içerir.

## 1. Dosya Yapısı
- `approle-policy.hcl`: Uygulama AppRole’ları için path-temelli politika şablonu.
- `k8s-policy.hcl`: Kubernetes ServiceAccount bağlamı için politika şablonu.
- `role-mapping.yaml`: Servislerin auth metotlarına göre bağlanacağı mapping örneği.

## 2. Kullanım Akışı
1. `docs/secret-standards.md` içindeki path standardına göre servis path’lerini belirleyin.
2. AppRole veya K8s metodu seçin:
   - VM tabanlı servisler → AppRole.
   - Kubernetes üzerinde çalışan servisler → K8s auth (ServiceAccount).
3. İlgili HCL şablonunu kopyalayın, `{env}` ve `{service}` alanlarını servis adına göre düzenleyin.
4. Politika oluşturma:
   ```bash
   vault policy write permission-service-prod policy.hcl
   ```
5. Auth mapping (örnek komutlar README’nin ilerleyen bölümlerinde).

## 3. CI Gate (gitleaks)
- `ci/gitleaks.toml` dosyasında Vault policy HCL dosyalarının plaintext secret içermediğini doğrulayan özel kural ekleyin.
- PR açıldığında:
  - HCL dosyalarının Vault path şablonu ile uyumlu olup olmadığı `scripts/validate-vault-paths.sh` ile kontrol edilir.
  - Politika isimlerinin `^{service}-{env}$` regex’i ile doğrulanması önerilir.

## 4. Operasyon Notları
- Politika değişiklikleri için Jira ticket şarttır; ticket numarası commit mesajında yer almalıdır.
- Prod ortamında politika güncellemeleri için Security onayı gereklidir.
- Her politika güncellemesinden sonra `vault audit` logu kontrol edilir.

## 5. Örnek Komutlar
```bash
# AppRole oluşturma
vault write auth/approle/role/permission-service \
    token_policies="permission-service-prod" \
    token_ttl="1h" token_max_ttl="4h"

# K8s auth role bağlama
vault write auth/kubernetes/role/user-service \
    bound_service_account_names="user-api-sa" \
    bound_service_account_namespaces="prod" \
    token_policies="user-service-prod" \
    token_ttl="1h" token_max_ttl="4h"
```

> Bu klasördeki şablonlar Terraform modüllerine entegre edilebilir; Faz 2’de HA senaryosuna geçerken yeniden kullanılacaktır.
