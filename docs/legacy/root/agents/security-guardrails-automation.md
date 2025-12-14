---
title: "Security Guardrails — ASVS Otomasyonu (Rapor Akışları)"
status: in_progress
owner: "@team/security"
workflow_tickets:
  - SEC-02
last_review: 2025-11-12
---

# AGENT NOTU – Normatif Kurallar
> Bu dosya `docs/agents` altında yer alan resmi security guardrails otomasyon kural setlerinden biridir.  
> LLM agent, güvenlik/compliance ile ilgili SPEC, STORY veya RUNBOOK üretirken buradaki maddeleri “MUST” olarak kabul eder; yalnız uygular/özelleştirir, yeniden tanımlamaz.  
> Kural değişikliği gerekiyorsa bu dosyanın kendisi review/commit ile güncellenir; başka dokümanlarda override yapılmaz.

Kapsam
- Rapor akışlarında CSP/SRI/route guard kontrolü; ASVS maddeleri için otomatik denetimler

Adımlar
1) CI job: `security-guardrails.yml` — CSP toplama, SRI doğrulama, route guard statik kontroller
2) Eşikler ve ihlal politikasını belirleyin (bloklayıcı/uyarı)
3) İhlal halinde otomatik issue/TODO açma

Kabul
- CI raporu üretiliyor (yeşil/kırmızı); ihlal başına issue açılıyor
- Rehber ve eşikler dokümante

İlgili Workflow (frontend)
- Yol: `../frontend/.github/workflows/security-guardrails.yml`
- Adımlar: SAST (Sonar/Semgrep), SRI doğrulama, SBOM, DAST (ZAP), CSP özet, artefakt upload
- Kabul: Workflow yeşil; SRI/CSP ihlali yok; DAST kritik bulgu yok (policy’ye göre)

Sonraki Adım
- Eşikler ve auto-issue davranışı için `security-guardrails.yml` içinde tanımlar güncellensin; sonuçlar Actions altında 2 ardışık yeşil olarak doğrulansın.

Eşik/Politika (varsayılan)
- Bloklayıcı koşullar:
  - Semgrep ERROR > 0 → kırmızı
  - ZAP High risk > 2 → kırmızı
  - SRI ihlali > 0 → kırmızı
  - CSP ihlali > 0 → kırmızı
- Uyarı (issue açılır, pipeline geçer): Semgrep WARNING (ERROR değil) — raporlanır ancak bloklamaz.
- Değişkenler: `SEMGREP_MAX_ERROR=0`, `ZAP_MAX_HIGH=2`, `SRI_MAX=0`, `CSP_MAX=0` (workflow env)

Otomatik Issue
- Script: `../frontend/scripts/security/create-issues.mjs` (etiketler: security, guardrails, SEC-02)
- Başlık biçimi: `[SEC-02] Guardrails ihlalleri — <kategori:adet, ...>`
- İçerik: SRI/DAST/SAST/CSP için eylem önerileri ve artefakt yolları
