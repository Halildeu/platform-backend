# Story E05-S01 – Canary, Flag & DR Guardrail’leri v1.0

- Epic: E05 – Release Safety & Security Pipeline  
- Story Priority: 510  
- Tarih: 2025-11-29  
- Durum: In Progress  
- Modüller / Servisler: ops-ci, platform-ops, frontend-shell, api-gateway, observability

## Kısa Tanım

Canary rollout, feature flag yönetişimi, DR/HA & edge fallback ve CI güvenlik zincirini bir araya getirerek manifest tabanlı platform için güvenli bir release hattı kurmak.

## İş Değeri

- Riskli değişiklikler kademeli devreye alınır; guardrail ihlalinde otomatik rollback ile kullanıcı etkisi minimize edilir.
- Feature flag borcu kontrol altında tutulur; kill-switch’ler ile problemli modüller hızla devre dışı bırakılabilir.
- SAST/DAST ve imzalı artefact’larla supply chain riskleri azaltılır; DR/HA stratejisiyle outage durumlarında platform devam eder.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E05-S01-RELEASE-SAFETY-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`  
- ADR: ADR-006 (Canary & Rollback), ADR-009 (Feature Flag Governance), ADR-010 (Security Pipeline), ADR-013 (DR/HA & Edge Strategy)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Argo CD + Harbor canary pipeline’ının %10 → %50 → %100 trafik adımlarıyla yapılandırılması.
- Unleash üzerinde `{domain}:{feature}:{variant}` adlandırma standardının oturtulması ve lifecycle kayıtlarının dokümante edilmesi.
- Canary guardrail metriklerinin tanımlanması (TTFA, hata oranı, Sentry hataları) ve ihlalde otomatik rollback akışının hazırlanması.
- DR/HA & edge konfigürasyonlarının (manifest/sözlük için multi-AZ, CDN cache, failover) temel senaryolarının uygulanması.
- CI pipeline’ına SAST/DAST + dependency taraması + SBOM üretimi + artefact imzası adımlarının eklenmesi.

### Out of Scope
- Her bir servis için detaylı DR runbook’ları (ayrı ops story’lerinde ayrıntılandırılır).
- Tüm feature flag’lerin geçmiş borcunun temizlenmesi (bu story yalnız yeni standardı getirir, temizleme için ayrı ticket’lar açılır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Modül/Servis | Task                                                        | Ready        | InProgress    | Review       | Done        |
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| ops-ci       | Canary pipeline aşamalarının (10/50/100) tanımlanması       | 2025-11-29   | 2025-11-29    | 2025-12-06   |             |
| observability| Guardrail metriklerinin seçimi ve dashboard’a eklenmesi     |              |               |              |             |
| ops-ci       | Guardrail ihlalinde otomatik rollback akışının kodlanması   |              |               |              |             |
| platform-ops | Unleash flag naming + lifecycle standardının yazılması      | 2025-11-29   | 2025-11-29    |              |             |
| platform-ops | DR/HA temel senaryoları için failover/fallback testleri     |              |               |              |             |
| ops-ci       | CI pipeline’a SAST/DAST + SBOM + imza adımlarının eklenmesi | 2025-11-29   | 2025-11-29    | 2025-12-06   |             |
| frontend-shell| E2E/smoke senaryolarıyla canary + DR akışının doğrulanması |              |               |              |             |
+--------------+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).
- Modül/Servis sütunu AGENT-CODEX §6.3.1 “Nerelere değecek?” adımının kaydıdır.

## Fonksiyonel Gereksinimler

1. Canary pipeline’ı guardrail metrikleri yeşil olduğu sürece otomatik ağırlık artırmalı; ihlalde rollback tetiklemelidir.
2. Yeni açılan her flag için lifecycle kaydı tutulmalı; stale flag sayısı hedeflenen eşik altında izlenmelidir.
3. DR drill’i sırasında manifest ve sözlük servisleri için otomatik fallback < 1 dk içinde gerçekleşmelidir.
4. SAST/DAST ve dependency taraması adımlarında kritik bulgu varken release olmaz; pipeline kırmızı kalmalıdır.

## Non-Functional Requirements

- Canary + DR + güvenlik adımları pipeline süresini kabul edilebilir sınırlar içinde tutmalıdır (hedef artış birkaç dakika).
- Rollback akışı ops ekipleri için tekrarlanabilir ve dokümante edilmiş olmalıdır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`

## Definition of Done

- [ ] Canary, flag governance, DR drill ve security pipeline için acceptance checklist’i sağlanmış olmalı.  
- [ ] Acceptance dosyasındaki maddeler (`docs/05-governance/07-acceptance/E05-S01-Release-Safety-and-DR.acceptance.md`) tam karşılanmış olmalı.  
- [ ] ADR-006/009/010/013 kararları uygulanmış olmalı.  
- [ ] Kod ve pipeline betikleri `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [ ] Kod/pipeline review’dan onay almış olmalı.  
- [ ] Guardrail/SAST/DAST/SBOM testleri yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Canary guardrail eşikleri gerçek trafik verisine göre tekrar kalibre edilebilir; DR drill sonuçları acceptance notlarına işlenmelidir.
- `.github/workflows/release-canary.yml` + `scripts/ci/canary/guardrail-check.mjs` akışı hem sample hem de `mode=live` seçenekleriyle çalışıyor; live modunda Grafana/Prometheus proxy’sinden gerçek TTFB/hata/Sentry/audit metrikleri çekiliyor ve gerekli PromQL ifadeleri repo Secrets/Variables aracılığıyla script’e aktarılıyor. Sample modu PoC ve lokal doğrulamayı sürdürmek için korunuyor.
- `.github/workflows/security-guardrails.yml` pipeline’ı SpotBugs SAST, OWASP Dependency-Check, ZAP baseline DAST ve CycloneDX SBOM + cosign imza adımlarını çalıştıracak şekilde güncellendi; raporlar `security-reports` artefact’ı altında toplanıyor.
- `docs/04-operations/01-runbooks/54-unleash-flag-governance.md` yayınlanarak `{domain}:{feature}:{variant}` naming kuralı, lifecycle checklist’i ve manifest örneği kanonik hale getirildi; Backstage flag kaydı ve cleanup süreçleri buradan yönetilecek.
- `docs/04-operations/assets/unleash/feature-flags.yaml` dosyası ilk aktif flag envanterini içeriyor (Access grid bayrakları + shell/security kill-switch seti) ve runbookta tanımlanan manifest formatıyla Backstage/Argo CD pipeline’larının tek kaynağı haline getirildi.

## Dependencies

- ADR-006 – Canary & Rollback Stratejisi.
- ADR-009 – Feature Flag Governance.
- ADR-010 – Security Pipeline.
- ADR-013 – DR/HA & Edge Stratejisi.

## Risks

- Canary guardrail metriklerinin yanlış eşiklerle tanımlanması ve gereksiz rollback’lere yol açması.
- Güvenlik adımlarının bakımının ihmal edilmesi ve zamanla devre dışı bırakılması.

## Flow / Iteration İlişkileri

| Flow ID   | Durum    | Not                                                              |
|-----------|---------|------------------------------------------------------------------|
| Flow-03   | Planned | Canary, flag yönetişimi, DR/HA ve security pipeline için ilk dalga. |
