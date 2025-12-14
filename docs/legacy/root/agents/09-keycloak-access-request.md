---
title: "Keycloak Admin Erişim Talep Süreci"
status: published
owner: "@security-platform"
last_review: 2025-11-04
tags: ["security", "keycloak", "access-management"]
---

# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi Keycloak admin erişim talep süreci kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

# Keycloak Admin Erişim Talep Süreci

Bu doküman, Keycloak Admin Console üzerinde kalıcı veya geçici erişim isteyen ekip üyelerinin izlemesi gereken adımları tanımlar. Amaç, üretim ortamındaki yetkilerin kontrollü ve izlenebilir şekilde verilmesini sağlamaktır.

## 1. Kapsam

- **Ortamlar:** Stage ve Production Keycloak (`https://keycloak-stage.example.com`, `https://keycloak.example.com`)
- **Roller:** `kc-admin`, `kc-support`, `security-auditor`
- **Erişim türleri:** Kalıcı (maks. 30 gün, otomatik yenileme yok), Break-glass (maks. 4 saat)

## 2. Talep Akışı

1. **JIRA Talebi Aç:** `SECURITY` projesinde “Keycloak Admin Access Request” şablonunu seç.  
   - Gerekli alanlar: Talep eden kişi, talep edilen rol, ortam, gerekçe, başlama/bitirme zamanı.
2. **Onay Zinciri:**  
   - Team lead onayı → Security Platform lead → Compliance (production talepleri için).  
   - Break-glass taleplerinde PagerDuty “Security Incident Commander” onayı eklenir.
3. **Secrets Yönetimi:** Talep onaylandıktan sonra `keycloak-admin-guide.md` ve `keycloak-realm-backup.md` içerisinde listelenen denetim adımları devreye alınır.
4. **Audit Kaydı:** Talebin JIRA ID’si ve onay sahipleri SIEM loguna (`access-approvals` index’i) eklenir. Talep kapandığında log “revoked” olarak güncellenir.

## 3. JIRA Şablonu

| Alan | Açıklama |
| --- | --- |
| Summary | `Keycloak Access - <env> - <role>` |
| Description | Erişim amacı, yapılacak işlemler ve beklenen etkiler |
| Access Type | `Permanent` / `Break-glass` |
| Environment | `stage` / `production` |
| Role | `kc-admin`, `kc-support`, `security-auditor` |
| Start / End Time | ISO-8601 formatı |
| Related Change / Incident | JIRA linki veya `N/A` |

Form otomasyonu (`scripts/jira/create-keycloak-access-request.mjs`) gelecekte eklenecek; geliştiriciler CLI’dan talep açabilecek.

## 4. Onay Sonrası Uygulama

1. Security platform görevlisi Keycloak Admin Console’da kullanıcıyı ilgili role ekler.  
2. Eklenen rolün otomatik süresi için `Scripts → Role Timeout` policy hazırlanır (ör. 30 gün).  
3. Kullanıcıya Slack üzerinden erişim penceresi ve sorumluluklar bildirilir:
   - Export/out-of-band işlem yapmayın.  
   - İşlem tamamlandığında `SECURITY-<ID>` talebine yorum bırakın.  
4. Gerekirse break-glass token’ı `vault kv get secret/keycloak/breakglass` üzerinden paylaşılır (opsiyonel). Paylaşım sonrası audit log güncellenir.

## 5. Erişim Sonlandırma

- Süresi dolan roller günlük cronjob (`infra/keycloak/cron/revoke-temporary-roles.yaml`) ile kaldırılır.  
- Manuel revoke gerektiğinde Security platform ekibi:  
  ```bash
  kcadm.sh remove-roles --u <username> --rolename kc-admin --realm platform
  ```  
- Revoke işlemi sonrasında JIRA talebine “Revoked” yorumu bırakılıp kapatılır.

## 6. Runbook & Denetim Referansları

- `docs/04-operations/01-runbooks/keycloak-admin-guide.md`
- `docs/04-operations/01-runbooks/keycloak-realm-backup.md`
- `docs/04-operations/02-monitoring/security-dashboard.md`
- `docs/04-operations/01-runbooks/01-vault-runbook.md`

## 7. Açık Noktalar

- [x] CLI otomasyon script’i (`scripts/jira/create-keycloak-access-request.mjs`) hazır ve CI’da test ediliyor (`docs/agents/10-keycloak-access-request-cli.md`).  
- [x] Cronjob revoke çıktıları SIEM’e gönderilip dashboard’a eklenecek (bkz. `infra/keycloak/cron/revoke-temporary-roles.yaml`, `scripts/keycloak/revoke-temporary-roles.sh`).  
- [x] Break-glass rotasyon listesinin aylık kontrolü (doc hygiene toplantısı) → süreç `docs/04-operations/01-runbooks/keycloak-admin-guide.md` §7’de tanımlandı.
