# Story SEC-VAULT-FAILOVER-01 – Vault Fail-Fast Fallback & Monitoring Strategy

- Epic: SEC – Secret Yönetimi  
- Story Priority: 004  
- Tarih: 2025-11-22  
- Durum: Done

## Kısa Tanım

Vault fail-fast davranışı için operasyonel strateji oluşturmak: Vault down olduğunda servislerin davranışı, FE’ye anlamlı geri bildirim, izleme/alarmlar ve runbook.

## İş Değeri

- Secret kaynağına erişim yokken kontrollü davranış sağlar.  
- FE/ops ekipleri için daha anlaşılır hata yüzeyi sunar.  
- Availability riskini azaltır, SLA’yı korur.

## Bağlantılar (Traceability Links)

- ACCEPTANCE: `docs/05-governance/07-acceptance/SEC-VAULT-FAILOVER-01.acceptance.md`  
- ARCH: `backend/docs/01-architecture/01-system/01-backend-architecture.md`  
- STYLE/AGENT: `docs/00-handbook/STYLE-BE-001.md`, `docs/00-handbook/AGENT-CODEX.md`  
- Runbook/OPS: `docs/04-operations` (varsa ilgili runbook)

## Kapsam

### In Scope
- Vault down senaryosunda servis start/response davranışının tanımlanması.  
- FE için anlamlı hata (5xx yerine açıklayıcı) veya maintenance ekranı taslağı.  
- Monitoring/alert ve runbook referansı.  
- ARCH/PROJECT_FLOW güncellemeleri.

### Out of Scope
- Vault production cluster kurulumu (mevcut infra korunur).  
- Yeni secret rotasyonu (ayrı iş).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+------------+-------------+---------+---------+
| Task                                                        | Ready      | InProgress  | Review  | Done    |
+-------------------------------------------------------------+------------+-------------+---------+---------+
| Vault down davranışını belgele (start vs fail-fast)         | 2025-11-22 |             |         | 2025-11-29 |
| FE hata yüzeyi / maintenance mesaj taslağı                  | 2025-11-22 |             |         | 2025-11-29 |
| Monitoring + alert + runbook linkleri                       | 2025-11-22 |             |         | 2025-11-29 |
| ARCH + PROJECT_FLOW + session-log güncelle                  | 2025-11-22 |             |         | 2025-11-29 |
+-------------------------------------------------------------+------------+-------------+---------+---------+
```

## Durum (2025-11-29)
- **Vault davranışı belgelendi:** `docs/01-architecture/01-system/01-backend-architecture.md` içine fail-fast log akışı, FE’ye dönen 503 `vault_unavailable` ErrorResponse ve manuel drill adımları eklendi.
- **Runbook + alert zinciri:** `docs/04-operations/01-runbooks/vault-failfast-fallback.md` yayımlandı; yeni alert `Security_Vault_Failfast_Restarts` `docs/04-operations/02-monitoring/security-dashboard.md` altında dokümante edildi.
- **API sözleşmesi:** `docs/03-delivery/api/auth.api.md` dosyasına Vault outage sırasında dönen hatanın payload/headers sözleşmesi eklendi; Shell maintenance banner referansıyla FE yüzeyi netleştirildi.
- **Drill tamamlandı:** 2025-11-29 16:38 (UTC+3) tarihli drill’de `auth-service` prod profilde Vault’tan sırları çekerek başladı, Vault durdurulduğunda yeniden start almayı denediğinde `VaultConfigDataLoader` fail-fast hatasıyla CrashLoop’a düştü ve Gateway `X-Serban-Outage-Code: VAULT_UNAVAILABLE` header’lı 503 döndürdü. Vault tekrar ayağa kaldırılıp sırlar yazıldıktan sonra servis normal health sonuçlarına döndü.

## Fonksiyonel Gereksinimler

1. Vault erişilemezken servis davranışı (fail-fast vs degraded) dokümante ve test edilmiş olmalı.  
2. FE, Vault kaynaklı hatalarda anlaşılır mesaj almalı.  
3. Monitoring/alert devrede olmalı; runbook referansı eklenmeli.

## Non-Functional Requirements

- Operasyon: Alert eşik ve runbook erişilebilirliği.  
- Güvenlik: Vault token/log sızıntısı olmamalı.

## İş Kuralları / Senaryolar

- “Vault down” → servis start etmeyebilir; Gateway/FE 503 + açıklayıcı payload/log görür.  
- “Vault yavaş” → timeout/alert; FE’ye gecikme bilgisi iletilir.

## Interfaces (API / DB / Event)

### API
- Yok; davranış Gateway/servis error yüzeyinde.

## Acceptance Criteria

Bu Story için acceptance:  
`docs/05-governance/07-acceptance/SEC-VAULT-FAILOVER-01.acceptance.md`

## Definition of Done

- [x] Acceptance maddeleri sağlandı.  
- [x] Vault failover stratejisi ARCH ve runbook’ta yer aldı.  
- [x] FE hata yüzeyi için karar alındı/dokümante.  
- [x] PROJECT_FLOW, session-log güncellendi.  
- [x] Kod/konfig değişiklikleri review + CI’den geçti (varsa).

## Notlar

- Fail-fast=true şu an aktif; bu Story davranışı yönetilebilir hale getirecek.

## Dependencies
- QLTY-FE-KEYCLOAK-01 – prod/test güvenlik akışları.  
- QLTY-MF-ROUTER-01 – MF smoke testleri sırasında Vault kaynaklı hatalar için fallback planı.

## Risks
- Yanlış fallback seçimi SLA’yı etkileyebilir; ops/sre ile koordine edilmeli.

## Flow / Iteration İlişkileri
| Flow ID   | Durum    | Not |
|-----------|---------|-----|
| Flow-Secret-Resilience | Planned | Vault failover stratejisi |

## İlgili Artefaktlar
- `backend/docs/01-architecture/01-system/01-backend-architecture.md`
