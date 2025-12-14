# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi giz yönetimi (secret standards) kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Vault Secret Path Standardı

Faz 1 kapsamında tüm servislerin Vault üzerinden giz saklama ve erişimini standartlaştırmak amacıyla hazırlanmıştır. Path şablonu, kategori taksonomisi ve isimlendirme kurallarını içerir.

## 1. Genel İlke
- Her gizli veri, ortam (env), servis ve giz türüne göre tekil bir path altında tutulur.
- Path şablonu: `secret/{env}/{service}/{name}`
  - `{env}`: `dev`, `qa`, `staging`, `prod`, `dr` gibi ortam tanımı.
  - `{service}`: Servisin canonical adı (örn. `payment-gateway`, `user-service`).
  - `{name}`: Giz kategorisine bağlı olarak belirlenen kısa anahtar.
- Path’ler kebab-case kullanır; boşluk, büyük harf, özel karakter yoktur.
- Her path için owner, amaç, rotasyon politikası Confluence kayıtlarında (`secret-inventory`) tutulur.

## 2. Kategori Taksonomisi
| Kategori | Açıklama | Örnek Path | Rotasyon Varsayılanı | Not |
|----------|----------|------------|----------------------|-----|
| `jwt-keys` | JWT imzalama/ doğrulama anahtarları | `secret/prod/api-gateway/jwt-signing` | 90 gün | JWKS yayını ile senk |
| `db-creds` | Veritabanı kullanıcı adı/parolası | `secret/staging/user-service/db-reader` | Dinamik (Vault DB engine) | TTL 8 saat |
| `third-party` | Harici API anahtarları | `secret/prod/notification-service/sendgrid` | 60 gün | Sağlayıcıya göre değişir |
| `cloud-creds` | IaaS/PaaS erişim anahtarları | `secret/prod/file-service/aws-s3` | 30 gün | IAM rotasyon pipeline’ı |
| `tls-certs` | TLS/mTLS sertifikası ve private key | `secret/prod/auth-service/mutual-tls` | 24–48 saat | Vault PKI Faz 2 ile |
| `oauth` | OAuth client secrets, client id | `secret/qa/mfe-users/client-credentials` | 90 gün | Keycloak senk |
| `feature-flags` | Güvenlik hassas config flag’leri | `secret/dev/variant-service/abtest-seed` | Gerektiğinde | Non-sensitive ise KV yerine config repo |
| `integrations` | İç mikroservis ID/secret | `secret/prod/permission-service/internal-client` | 60 gün | Faz 1’de kaldırılan internal key yerine |
| `service-config` | Şifrelenmiş yapılandırma parametreleri | `secret/staging/mail-service/smtp-password` | 30 gün | Vault üzerinden decrypt |

> Listeye yeni kategori eklerken Security Architecture onayı alınır ve tablo güncellenir.

## 3. İsimlendirme Rehberi
- `{service}` değeri mikroservisin depo adıyla uyumlu olmalı (`user-service`, `permission-service`, `api-gateway`).
- `{name}` alanında kategoriye uygun kısa, açıklayıcı anahtar kullanılmalı:
  - JWT anahtarları için `jwt-signing`, `jwt-verification`, `jwks`.
  - Veritabanı erişimleri için `db-admin`, `db-reader`, `db-writer`.
  - Üçüncü parti servisler için sağlayıcı adı (`sendgrid`, `stripe`, `twilio`).
  - Çoklu kimlik bilgisi gereken durumlarda alt segment eklenebilir: `secret/prod/payment-service/third-party/stripe`.
- Path türünde sürümleme gerekiyorsa, KV `version` özelliği kullanılmalı; path’e revizyon numarası eklenmez.

## 4. Metadata & Dokümantasyon
- Her path için `secret-inventory` tablosunda şu alanlar tutulur:
  - Env, Service, Path
  - Kategori
  - Owner (Takım + kişi)
  - Rotasyon frekansı
  - Son rotasyon tarihi
  - Runbook referansı
- Path oluşturulduğunda CI/CD pipeline’larında otomatik erişim testi yapılır ve loglanır (`gitleaks` gibi araçlarla sızıntı engellenir).

## 5. Politika Eşlemesi
- AppRole/K8s auth politikaları path bazlı kısıtlanır:
  ```hcl
  path "secret/data/prod/permission-service/*" {
    capabilities = ["read", "list"]
  }
  ```
- Yazma yetkisi yalnız rotasyon pipeline’larına verilir; uygulama pod’ları `read` ile sınırlı tutulur.
- Path bazında sentetik izleme: Haftalık cron job `vault kv get` çalıştırarak erişimi doğrular, audit log’da kaydı oluşturur.

## 6. Onay & Değişiklik Süreci
1. Yeni path talebi Jira ticket’ı ile açılır; Security + Platform onayı alınır.
2. Path açıldıktan sonra Confluence envanteri güncellenir, `secret-inventory` PR’ı merge edilir.
3. Rotasyon pipeline’ı ve uygulama yetkilendirmesi güncellendikten sonra prod deploy yapılabilir.

---
**Sonraki Adım:** Servis bazlı politikalar için `infra/vault/policies/` ve uygulanmış örnekler için `docs/04-operations/01-runbooks/vault-approle-auth-service.md` rehberini kullanın; AppRole/K8s auth yapılarını buna göre güncelleyin.
