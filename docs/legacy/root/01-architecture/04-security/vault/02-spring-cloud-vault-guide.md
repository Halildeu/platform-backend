# Spring Cloud Vault Entegrasyonu & Fail-Fast Rehberi

Faz 1 kapsamında mikroservislerin Vault üzerinden secret çekmesi, secret yoksa fail-fast davranışı ve log/alarm eşikleri için yönergeler.

## 1. Konfigürasyon Şablonu
- Spring Boot uygulamalarında `bootstrap.yml` kullanılır (config server’dan önce yüklenir).
- Örnek `bootstrap.yml`:
  ```yaml
  spring:
    application:
      name: permission-service
    cloud:
      vault:
        uri: https://vault-01.${ENV}.corp:8200
        namespace: ""
        authentication: approle
        application-name: ${spring.application.name}
        fail-fast: true
        retry:
          enabled: true
          initial-interval: 2000
          max-attempts: 3
        generic:
          enabled: true
          backend: secret
          default-context: ${ENV}/${spring.application.name}
        session-mode: stateful
      config:
        import: vault://
  ```
- AppRole kimliği/secret ID:
  - `spring.cloud.vault.app-role.role-id` ve `secret-id` çevre değişkeni veya `/opt/vault/approle/` dosyalarında tutulur (tmpfs).

## 2. Secret Path Eşleme
- `default-context`: `secret/{env}/{service}` path’ini karşılar.
- Ek path gerektiğinde:
  ```yaml
  spring:
    cloud:
      vault:
        generic:
          application-name: ${spring.application.name}
          profile-separator: "/"
          default-context: ${ENV}/${spring.application.name}
          additional-contexts:
            - ${ENV}/shared
  ```
- Path standardı `docs/secret-standards.md` ile uyumlu olmalıdır.

## 3. Fail-Fast Davranışı
- `spring.cloud.vault.fail-fast=true` → Secret erişilemezse uygulama start’ı başarısız.
- Retry 3 deneme (2 sn aralık) sonrası hâlâ başarısız ise uygulama kapanır; Kubernetes veya systemd restart politikası devreye girer.
- Log mesajı:
  - ERROR `VaultConfigInitializationException: IllegalStateException: Secrets not obtainable`
  - Logda `corrId` ve `env` bilgisi bulunmalı.
- Alert eşiği:
  - 5 dakika içinde aynı servis 3 defa restart ederse PagerDuty alarmı (`VAULT-SECRET-FAIL`).

## 4. Lack of Secret Alerting
- Prometheus exporter (Actuator metrics) `application_start_failed_total` metriğini artırır.
- Grafana uyarısı:
  - IF `increase(application_start_failed_total{service="permission-service"}[5m]) > 0`.
- Ops kanalı uyarı mesajı `"[Vault Fail-Fast] permission-service secrets missing in prod"` formatında.

## 5. Secret Rotasyon & Refresh
- Actuator `/refresh` endpoint’i devre dışıysa (default), secret rotasyonu deployment veya rolling restart ile uygulanır.
- Faz 2’de hot reload stratejisi için bu rehber referans alınacak.
- Secret güncellemesi sonrası:
  - Vault audit logu erişimi doğrular.
  - Servis restart edilip `vault kv get secret/{env}/{service}` değerinin loglandığı smoke test yapılır.

## 6. Örnek AppRole ConfigMap (K8s)
```yaml
apiVersion: v1
kind: Secret
metadata:
  name: permission-service-approle
type: Opaque
stringData:
  role-id: "<vault-role-id>"
  secret-id: "<vault-secret-id>"
---
apiVersion: apps/v1
kind: Deployment
spec:
  template:
    spec:
      containers:
        - name: permission-service
          env:
            - name: SPRING_CLOUD_VAULT_APP_ROLE_ROLE_ID
              valueFrom:
                secretKeyRef:
                  name: permission-service-approle
                  key: role-id
            - name: SPRING_CLOUD_VAULT_APP_ROLE_SECRET_ID
              valueFrom:
                secretKeyRef:
                  name: permission-service-approle
                  key: secret-id
```

## 7. Log & Observability
- JSON log formatında `log_type=vault` etiketi ile ayrı tutulur.
- Kibana araması: `service.keyword:"permission-service" AND log_type:vault AND level:ERROR`.
- Metricleri `vault_client_secret_fetch_duration_seconds` ile ölçün; P95 > 500 ms ise uyarı.

## 8. Gereken Aksiyonlar
1. Her servis için `bootstrap.yml` ekle, Vault URI ve AppRole bilgilerini tanımla.
2. Vault AppRole secret-ID rotation pipeline’ı kur (haftalık).
3. CI pipeline’ına `./scripts/test-vault-failfast.sh` ekleyin (Vault yoksa uygulama logging’i DOA).
4. Ops runbook’ta (Faz 3) fail-fast alarm yanıt adımlarını belgele.
5. Auth-service için `security.service-jwt.*` değerlerini Vault KV (`secret/{env}/auth-service/service-jwt`) üzerinden sağlayın; staging/prod rollout’larda anahtar rotasyon runbook’unu (`docs/service-jwt-keys.md`) takip edin.

---
**Sonraki Adım:** JWT geçişi sonrası feature flag temizliği ve kill-switch dokümantasyonu için güncelleme yapın.
