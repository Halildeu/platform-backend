---
title: "Keycloak Realm Yedekleme & Geri Yükleme Rehberi"
status: draft
owner: "@security-platform"
last_review: 2025-11-03
tags: ["security", "keycloak", "backup"]
---

# Keycloak Realm Yedekleme & Geri Yükleme Rehberi

Bu doküman `platform` realm’inin periyodik yedeklenmesi ve gerektiğinde geri yüklenmesi için izlenmesi gereken adımları içerir. Yedekler, Keycloak admin etkinlik rehberinde (bkz. `docs/04-operations/01-runbooks/keycloak-admin-guide.md`) belirtilen denetim gereksinimlerinin tamamlayıcısıdır.

## 1. Yedekleme Politikası

- **Sıklık:** Günlük otomatik export + her kritik değişiklik sonrası manuel export.
- **Saklama:** 30 günlük döngü (S3 bucket `s3://kc-backups/platform/`) + haftalık şifrelenmiş arşiv (Vault KV).
- **Şifreleme:**  
  - S3 objeleri KMS anahtarı `alias/kc-platform-backup` ile şifrelenir.  
  - Manuel export dosyaları (`.json`) şifreli zip (`zip -e`) ile Vault KV `secret/kc/backups/manual` altına yüklenir.
- **Erişim:** SRE + Security platform ekipleri (break-glass onayı). Audit log her indirme için zorunlu (CloudTrail / SIEM).

## 2. Manuel Export Adımları

1. Keycloak Admin Console → `Realm Settings → General → Export`.  
2. `Export groups, clients, and roles` seçeneğini işaretleyin.  
3. `Export` butonuna basın; export dosyası (`platform-realm.json`) indirilir.  
4. Localde dosyayı imzalama:  
   ```bash
   shasum -a 256 platform-realm.json > platform-realm.json.sha256
   ```
5. Şifreli arşiv oluşturun:  
   ```bash
   zip -e platform-realm-$(date +%Y%m%d).zip platform-realm.json platform-realm.json.sha256
   ```
6. Zip dosyasını Vault KV’ye yükleyin (`vault kv put secret/kc/backups/manual/<tarih> data=@platform-realm-YYYYMMDD.zip`).  
7. `keycloak-admin-guide.md` içindeki change log’a not düşüp JIRA ticket’ı kapatın.

## 3. Otomatik Yedekleme (CronJob)

Kubernetes CronJob manifest örneği (`infra/keycloak/realm-backup-cronjob.yaml`):

```yaml
apiVersion: batch/v1
kind: CronJob
metadata:
  name: keycloak-realm-backup
spec:
  schedule: "0 2 * * *"
  jobTemplate:
    spec:
      template:
        spec:
          serviceAccountName: keycloak-backup-sa
          containers:
            - name: realm-backup
              image: ghcr.io/platform/keycloak-backup:latest
              env:
                - name: KC_BASE_URL
                  value: "https://keycloak.example.com"
                - name: KC_REALM
                  value: "platform"
                - name: KC_CLIENT_ID
                  valueFrom:
                    secretKeyRef:
                      name: kc-backup-client
                      key: client-id
                - name: KC_CLIENT_SECRET
                  valueFrom:
                    secretKeyRef:
                      name: kc-backup-client
                      key: client-secret
                - name: S3_BUCKET
                  value: "s3://kc-backups/platform"
                - name: AWS_REGION
                  value: "eu-central-1"
              args:
                - /app/export.sh
          restartPolicy: OnFailure
```

Backup container script (`export.sh`) Keycloak Admin REST API’sini kullanarak export alır ve S3’e yükler. Şema/günlükler `logs-keycloak-backup-*` indexine gönderilir. Script ve Docker image kaynağı: `infra/keycloak/backup/`.

## 4. Geri Yükleme (Restore) Adımları

> **Uyarı:** Restore işlemi mevcut realm verilerini üzerine yazar. Üretim restore’ları için change approval zorunludur.

1. En güncel yedeği belirleyin (`s3://kc-backups/platform/` veya Vault KV).  
2. Dosyaları indirip doğrulayın:  
   ```bash
   aws s3 cp s3://kc-backups/platform/platform-realm-2025-11-03.json .
   shasum -a 256 -c platform-realm-2025-11-03.json.sha256
   ```
3. Keycloak admin kullanıcı kimlik bilgilerini hazır edin (break-glass).  
4. REST API ile restore:  
   ```bash
   curl -X POST \
     -H "Content-Type: application/json" \
     -H "Authorization: Bearer $TOKEN" \
     "https://keycloak.example.com/admin/realms/platform" \
     --data @platform-realm-2025-11-03.json
   ```
5. Alternatif: Admin console → `Realm Settings → Import` → JSON dosyasını yükleyin.  
6. Restore sonrası doğrulama:
   - Keycloak → `Realm Settings → Keys` (aktif key’ler)  
   - `Users`, `Clients`, `Roles` ekranları  
   - Grafana `security/keycloak-admin-activity` paneli (admin event log var mı?)  
   - Gateway smoke test (`/api/auth/health`) ve `mfe-security` sayfa açılışı.

## 5. Test & Doğrulama

- Quarterly restore drill (Stage ortamında) zorunludur.  
- Drill planı: `docs/04-operations/01-runbooks/vault-restore-drill.md` referans alınarak Keycloak + Vault birlikte test edilir.  
- Drill çıktıları Confluence “Security DR Drills” sayfasına eklenir.

## 6. İlgili Dokümanlar

- `docs/04-operations/01-runbooks/keycloak-admin-guide.md`
- `docs/agents/03-secret-rotation-pipeline.md`
- `docs/04-operations/01-runbooks/vault-restore-drill.md`
- `docs/04-operations/02-monitoring/security-dashboard.md`

## 7. Açık Noktalar

- [ ] Backup container `export.sh` script’i için unit test yazılması (infra repo).  
- [ ] S3 retention politikasını Terraform state’e eklemek.  
- [ ] Restore sonrası smoke test script’i (Playwright) CI pipeline’a entegre edilmeli.
