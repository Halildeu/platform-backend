---
title: "Platform Glossary"
status: in-progress
owner: "@team/platform-docs"
last_review: 2025-11-03
---

# Platform Glossary

Bu sözlük; mikro-frontend platformunda sık kullanılan terimlerin herkes tarafından aynı anlamla kullanılmasını sağlar. Her terim için kısa tanım, pratik kullanım örneği ve ilgili doküman referansları yer alır. Yeni terim eklerken `docs/05-templates/06-glossary/glossary-entry.md` şablonunu kullanın.

## 1. Mimari & Ürün Terimleri

| Terim | Tanım | Kullanım / Notlar |
| --- | --- | --- |
| Manifest | Mikro-frontend ekranını tanımlayan JSON/yapılandırma dosyası; PageLayout bileşenleri, grid sütunları, aksiyonlar burada tanımlanır. | Ayrıntı: `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`) |
| Shell | MFE’lerin host uygulaması; servis sözleşmelerini, auth context’i ve ortak UI bileşenlerini sağlar. | ADR referansı: `docs/05-governance/05-adr/ADR-002-page-layout-and-manifest-model.md` |
| Access Module | `mfe-access` uygulaması; rol ve izin yönetimini manifest tabanlı sunar. | Runbook: `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` |
| Audit ID | Mutasyon yanıtlarında dönen benzersiz kimlik; audit log ekranına deep-link sağlar. | Üretim akışı: `frontend/docs/architecture/frontend/ux/planned-mfe-ux.md` |
| TTFA (Time to First Action) | Kullanıcının ilk etkileşim yapabildiği ana kadar geçen süre; performans SLO metriği. | Gözlemleme: `docs/04-operations/01-runbooks/` |

## 2. Süreç & Operasyon Terimleri

| Terim | Tanım | Kullanım / Notlar |
| --- | --- | --- |
| Doc Hygiene | Sprint sonunda dokümanların güncelliğini kontrol eden ritüel; backlog maddeleri burada gözden geçirilir. | Süreç: `docs/01-architecture/01-system/03-architecture-governance.md` |
| Feature Flag | Unleash üzerinde yönetilen, MFE davranışını koşulsal değiştiren yapı. | Yönetişim: `docs/05-governance/05-adr/ADR-009-feature-flag-governance.md` |
| Guardrail | CI/CD üzerinde kırmızı çizgi adımları (ör. i18n-smoke, security-guardrails). | Örnek: `docs/03-delivery/02-ci/02-security-guardrails.md`, `.github/workflows/i18n-smoke.yml` |
| Runbook | Incident anında uygulanacak prosedürlerin bulunduğu operasyon dokümanı. | Şablon: `docs/03-delivery/templates/03-runbook/runbook-template.md` |
| Rollback | Başarısız release sonrası en son stabil deploy’a geri dönme adımları. | Strateji: `docs/05-governance/05-adr/ADR-006-canary-and-rollback-strategy.md` |

## 3. Lokalizasyon & Güvenlik Terimleri

| Terim | Tanım | Kullanım / Notlar |
| --- | --- | --- |
| TMS (Translation Management System) | Çeviri sözlüklerinin saklandığı servis; `npm run i18n:pull` komutu ile çekilir. | Ayrıntı: `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`) |
| Pseudolocale | TMS’ten üretilen çeviri dosyası; UI çeviri eksiklerini ve taşmaları yakalamak için kullanılır. | Workflow: `.github/workflows/i18n-smoke.yml` |
| SRI (Subresource Integrity) | Manifest ve remote bundle’lar için hash doğrulaması; güvenli yükleme sağlar. | Rehber: `frontend/security/sri-manifest.json`, `docs/05-governance/05-adr/ADR-001-remote-manifest-and-sri.md` |
| Vault | Gizli anahtar yönetimi ve dinamik DB secrets için kullanılan HashiCorp Vault kurulumumuz. | Rehber: `docs/04-operations/01-runbooks/vault-config.md`, `docs/04-operations/01-runbooks/vault-db-secrets.md` |
| Service Token | Servisler arası kimlik doğrulamada kullanılan token tipi; AppRole veya statik key ile üretilir. | Referans: `docs/01-architecture/04-security/identity/05-service-jwt-keys.md` |

## 4. Telemetry & Observability Terimleri

| Terim | Tanım | Kullanım / Notlar |
| --- | --- | --- |
| OTEL (OpenTelemetry) | Uygulama metrik/log/trace toplamak için kullanılan standard. | ADR: `docs/05-governance/05-adr/ADR-011-observability-correlation.md` |
| SendBeacon Fallback | Shell telemetry client’ının tarayıcı kapanırken veri göndermek için kullandığı yöntem. | Kod: `frontend/apps/mfe-shell/src/app/telemetry/telemetry-client.ts` |
| Synthetic Check | Grafana üzerinden TTFA ve uptime için koşulan otomatik test. | Dashboard referansı: `docs/04-operations/01-runbooks/52-mfe-access-runbook.md` §3 |
| Error Budget | SLO hedefi aşıldığında kalan hata payı; release kararlarında referans alınır. | Açıklama: `docs/01-architecture/01-system/03-architecture-governance.md` |

## 5. Ek Terimler & Kısaltmalar

- **MFE:** Micro Frontend.
- **ADR:** Architecture Decision Record.
- **SLA/SLO:** Service Level Agreement / Objective.
- **CI/CD:** Continuous Integration / Deployment.
- **TTI:** Time to Interactive (baz performans metriği; TTFA ile birlikte izlenir).

> Güncelleme notu: Yeni terim eklendiğinde bu sözlükte alfabetik sırayı koruyun ve ilgili doküman linklerini eklemeyi unutmayın.
