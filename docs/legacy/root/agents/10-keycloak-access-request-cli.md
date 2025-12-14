---
title: "Keycloak Access Request CLI"
status: published
owner: "@security-platform"
last_review: 2025-11-04
tags: ["security", "automation", "jira"]
---

# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan Keycloak access request CLI için **normatif teknik spesifikasyon**dur.  
> LLM agent, CLI ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# SPEC-AGENT-KEYCLOAK-ACCESS-CLI
**Başlık:** Keycloak Access Request CLI  
**Versiyon:** v1.0  
**Tarih:** YYYY-AA-GG  

**İlgili Dokümanlar:**  
- STORY: (örn. `STORY-SEC-KEYCLOAK-CLI`)  
- ADR: (örn. `ADR-AGENT-KEYCLOAK-CLI`)  
- ACCEPTANCE: (örn. `AT-AGENT-KEYCLOAK-CLI`)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

## 1. Amaç (Purpose)

`scripts/jira/create-keycloak-access-request.mjs` komut satırı aracının davranışını tanımlar.  
Amaç, Keycloak admin erişim talepleri için JIRA ticket açma sürecini standartlaştırmak; manuel form doldurmayı azaltmak ve onay akışını güvenli/izlenebilir hale getirmektir.

---

## 2. Görev Tanımı (Responsibilities)

- Parametrelerden geçerli bir Keycloak access request payload’u üretmek.  
- JIRA API üzerinden ilgili projede (varsayılan `SECURITY`) ticket oluşturmak.  
- Parametreleri ve iş kurallarını (break-glass süresi, rol/env enum’ları vb.) doğrulamak.  
- Çalışma sonucu audit log satırı ve opsiyonel SIEM event’i üretmek.  

---

## 3. Tetikleyiciler (Triggers)

- Manuel geliştirici/ops çağrısı (lokal veya jump host üzerinden).  
- CI/CD pipeline içindeki adım (örn. `security-guardrails.yml` içinde otomatik tetikleme).  

---

## 4. Girdi–Çıktı (Input / Output)

### 4.1. Input – CLI Parametreleri

Komut sözleşmesi:

```bash
node scripts/jira/create-keycloak-access-request.mjs \
  --env production \
  --role kc-admin \
  --type break-glass \
  --start 2025-11-05T08:00:00Z \
  --end 2025-11-05T12:00:00Z \
  --reason "Incident SEC-123 audit" \
  --change SEC-321 \
  --assignee "@alice"
```

Parametre tablosu:

| Parametre | Açıklama | Zorunlu | Not |
| --- | --- | --- | --- |
| `--env` | `stage` veya `production` | ✅ | Enum validation |
| `--role` | `kc-admin` / `kc-support` / `security-auditor` | ✅ | Enum validation |
| `--type` | `permanent` / `break-glass` | ✅ | Break-glass süresi kısıtlı |
| `--start`, `--end` | ISO‑8601 tarih/zaman | ✅ | `start < end`, UTC |
| `--reason` | Talep gerekçesi | ✅ | Min 10 karakter |
| `--change` | İlgili değişiklik/incident ID | ❌ | Opsiyonel |
| `--assignee` | JIRA kullanıcı adı | ❌ | Varsayılan: talep eden |
| `--dry-run` | JIRA’ya yazmadan payload göster | ❌ | Validation/test amaçlı |

### 4.2. Input – Konfigürasyon

- `.env` değişkenleri:
  - `JIRA_BASE_URL`
  - `JIRA_USERNAME`
  - `JIRA_API_TOKEN`
  - `JIRA_PROJECT_KEY` (varsayılan `SECURITY`)
  - `JIRA_TEMPLATE_ID` (form-based automation kullanılıyorsa)
- Opsiyonel SIEM entegrasyonu:
  - `SIEM_WEBHOOK_URL`
  - `SIEM_WEBHOOK_AUTH`
- Opsiyonel konfig dosyası:
  - `config/jira-access-request.json` → default assignee, approver listesi vb.

### 4.3. Output

- Başarılı durumda:
  ```json
  { "key": "SECURITY-1234", "url": "https://jira.example.com/browse/SECURITY-1234" }
  ```
- Hata durumunda:
  - Non‑zero exit code (ör. 1 = validation, 2 = network/JIRA hatası).  
  - STDERR’de hata mesajı.  
- Log dosyası: `logs/jira-access-request.log` (bkz. İzlenebilirlik).  

---

## 5. İş Kuralları (Business Rules)

- `--type break-glass` taleplerinde `end - start` süresi **4 saati aşamaz**.  
- `--env` değeri yalnız `stage` veya `production` olabilir.  
- `--role` değeri yalnız `kc-admin`, `kc-support`, `security-auditor` olabilir.  
- `--reason` en az 10 karakter olmalı; boş veya anlamsız metin (örn. “test”) reddedilir.  
- Başarılı ticket açılışında log ve (varsa) SIEM event’i üretilmesi zorunludur.  

---

## 6. Süreç Akışı (Process Flow)

1. CLI çağrısı ile parametreler alınır.  
2. Parametreler parse edilir ve validasyon kuralları uygulanır.  
3. JIRA payload’u şablona göre hazırlanır.  
4. `--dry-run` ise payload stdout’a yazılır, JIRA çağrısı yapılmaz.  
5. Normal çalışmada JIRA API’ına HTTP isteği gönderilir.  
6. Başarılı yanıt alınırsa:
   - Ticket `key` ve `url` değeri stdout’a yazılır.
   - Audit log satırı `logs/jira-access-request.log` dosyasına append edilir.
   - Opsiyonel olarak SIEM webhook’una event POST edilir.  
7. Hata durumunda uygun exit code ve hata mesajı ile çıkılır.  

---

## 7. Validasyon Kuralları (Validation)

- Eksik parametre:
  - Zorunlu parametrelerden biri yoksa usage mesajı gösterilir, exit code `1`.  
- Break‑glass süresi:
  - `--type break-glass` ve `end - start > 4 saat` → validation hatası.  
- Enum alanları:
  - `--env`, `--role`, `--type` yalnız tanımlı değerleri kabul eder.  
- Tarih formatı:
  - `--start` ve `--end` ISO‑8601 formatında olmalı; parse edilemezse hata.  

---

## 8. Performans & NFR

- Tek çağrı için beklenen toplam süre: JIRA API yanıt süresine bağlı olmakla birlikte **p95 < 2 sn**.  
- CLI idempotent değildir; aynı parametrelerle yeniden çalıştırıldığında ikinci bir ticket açabilir (istenirse future work).  
- Network hatalarında makul sayıda retry (örn. 3 deneme, exponential backoff) uygulanabilir.  

---

## 9. Güvenlik

- JIRA API token’ı `.env`/secrets üzerinden okunur; CLI parametresi ile geçirilmez.  
- Hata mesajlarında veya loglarda `JIRA_API_TOKEN` ve diğer gizli bilgilerin plaintext geçmesi yasaktır.  
- Kullanıcı bilgileri veya hassas içerikler loglara yalnız gerekli minimum detayla yazılır.  

---

## 10. İzlenebilirlik (Observability)

- Log formatı:
  ```text
  [2025-11-03T12:01:00Z] Keycloak access request created | ticket=SECURITY-1234 | env=production | role=kc-admin | type=break-glass
  ```
- Log dosyası:
  - Yol: `logs/jira-access-request.log`  
  - Rotasyon: en az 7 gün saklama (opsiyonel logrotate).  
- SIEM entegrasyonu:
  - `SIEM_WEBHOOK_URL` tanımlıysa aynı içerik HTTP POST ile SIEM’e gönderilir.  

---

## 11. Traceability (İzlenebilirlik Zinciri)

- Süreç standardı: `docs/agents/09-keycloak-access-request.md`  
- İlgili runbook’lar:
  - `docs/04-operations/01-runbooks/keycloak-admin-guide.md`  
  - `docs/04-operations/01-runbooks/keycloak-realm-backup.md`  
- İlgili dashboard/gözlem:
  - `docs/04-operations/02-monitoring/security-dashboard.md`  

---

## 12. Riskler

- JIRA API değişiklikleri veya field ID güncellemeleri CLI’yi kırabilir; payload şablonu düzenli aralıklarla doğrulanmalıdır.  
- Yanlış parametrelerle (ör. hatalı env/role) açılan ticket’ların manuel cleanup ihtiyacı.  
- SIEM entegrasyonu başarısız olursa sadece lokal log kalır; alarm eşikleri buna göre ayarlanmalıdır.  

---

## 13. Notlar

- Gelecek adım: revoke cron loglarının SIEM ve dashboard entegrasyonu tamamlanmalı.  
- Alan isimleri ve custom field’lar kurum içi JIRA şemasına göre güncellenirken bu doküman da eş zamanlı güncellenmelidir.
