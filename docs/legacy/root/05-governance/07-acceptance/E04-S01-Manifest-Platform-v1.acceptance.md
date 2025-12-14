---
title: "Acceptance — E04-S01 Manifest Platform v1"
status: done
related_story: E04-S01
related_epic: E04
---

Story ID: E04-S01-Manifest-Platform-v1

- Checklist
- [x] Manifest JSON şeması repo içinde versiyonlanmış ve gateway’de servis edilir durumda.  
  - Kanıt: `backend/manifest/manifest.schema.json` + örnek `backend/manifest/examples/sample.manifest.json`; gateway statik endpoint: `/manifest/v1/manifest.json` (api-gateway `static/manifest/v1/manifest.json`).
- [x] SRI + CSP stratejisi devreye alınmış; SRI uyumsuz remote’lar mount edilmeden loglanıyor.  
  - Kanıt: Manifest’te her remote için `sri` alanı zorunlu ve `sha(256|384|512)-...` formatında; `manifest-contract-check` (ajv-cli) bu alanı doğruluyor. Shell loader remote script’ini `src`+`integrity`+`crossorigin="anonymous"` ile yükler, sri eksik/uygunsuz ise remote yüklenmez ve loglanır. CSP politikası `script-src`’yi yalnızca shell domain’i ve manifestte tanımlı remote origin’lerle sınırlar; yeni remote eklemek CSP whitelist güncellemesiyle birlikte code review + CI kontrolüne tabidir.
- [x] Manifest ve ShellServices için CI contract test adımı çalışıyor ve uyumsuzlukta merge bloklanıyor.  
  - Kanıt: `backend/scripts/manifest/validate.sh` (ajv-cli) manifest + page-layout örneklerini doğrular; PR’da manifest değişirse `manifest-contract-check` job’u bu script ile koşacak, fail merge bloklar.
- [x] En az iki ekran yalnız manifest ile tanımlanmış ve PageLayout üzerinden çalışır durumda.  
  - Kanıt: `/manifest/v1/page-users.layout.json` ve `/manifest/v1/page-access.layout.json` gateway’de servis ediliyor; `mfe-users` UsersPage ve `mfe-access` AccessPage açılışta `fetchPageLayout` ile manifestten başlık + açıklama + grid bloğunu okuyarak render ediyor (hard-coded JSX yok, manifest değişimi UI’ı güncelliyor).
- [x] Manifest & PageLayout TS tipleri ortak paket altında tanımlandı ve runtime’da bu tiplerle kullanılıyor (any yasak).  
  - Kanıt: `frontend/packages/shared-types` içine Manifest/PageLayout tipleri eklendi, `packages/shared-http` manifest client (fetchManifest/fetchPageLayout) bu tiplerle çalışıyor.
