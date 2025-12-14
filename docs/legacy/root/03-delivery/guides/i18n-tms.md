## i18n TMS Entegrasyonu

### Amaç
- Çeviri yönetim sistemi (TMS) ile sözlüklerin çekilmesi ve CI süreçlerine bağlanması.

### Çalışma Akışı
1. `frontend/.env.i18n.example` dosyasını kopyalayıp (ör. `.env.i18n.local`) TMS erişim bilgilerini doldur.  
   - `TMS_BASE_URL` – Tolgee/Weblate instance adresi  
   - `TMS_API_TOKEN` – machine token  
   - `TMS_ENV` – isteğe bağlı ortam adı (dev/stage)  
   - `I18N_LOCALES` ve `I18N_NAMESPACES` – pipeline’da işlenecek diller/namespace’ler
2. Terminalde `cd frontend` ➜ `source .env.i18n.local` veya CI’de `env` olarak tanımla.
3. Komutlar:
   - `npm run i18n:pull` → `packages/i18n-dicts/scripts/fetch-translations.ts` çalışır; TMS → `src/locales/**` dosyalarını günceller ve `manifest.json` versiyonlar.
   - `npm run i18n:pull -- --local-only` → TMS yerine mevcut sözlükleri hash’leyip manifest’i yeniler (offline doğrulama).
   - `npm run i18n:pseudo` → `scripts/generate-pseudolocale.ts` tetiklenir; pseudo sözlükleri aynı pakete yazar.
4. Çekiş sonrasında `git status` kontrol edilir; yeni sözlükler commit edilir ve ilgili Story/Acceptance’a referans yazılır.

### Pseudolocale & Eksik Key Telemetry
- `npm run i18n:pseudo` → `packages/i18n-dicts/scripts/generate-pseudolocale.ts` komutu TR/EN sözlüklerini temel alarak `src/locales/pseudo/**` dosyalarını günceller.
- CI’de `i18n-a11y-smoke.yml` workflow’u içinde `npm run i18n:pull -- --local-only` + `npm run i18n:pseudo` adımları çalıştırılır; böylece eksik key raporlaması her PR’da doğrulanır.
- Pseudolocale çıktıları QA-02 checklist’leriyle birlikte incelenir; eksik string varsa telemetry (`missingKey` metriği) < %0.5 eşik hedeflenir.

### CI Smoke (Pseudolocale & Missing Key)
- `.github/workflows/i18n-a11y-smoke.yml` workflow’u PR’larda `npm run i18n:pull -- --local-only`, `npm run i18n:pseudo`, `npm run quality:audit:build` ve `npm run test:a11y` komutlarını çalıştırır.
- Hata olduğunda PR kırmızıya çekilir, `axe` raporları artefact olarak yüklenir (QA-02 checklist’i en az bir grid + form ekranına uygulanır).

### Kabul Kriterleri
- `@mfe/i18n-dicts` güncel; manifest ve MFE ekranları key‑tabanlı.
- Pseudolocale render gate çalışır; fallback telemetrisi panolarda görünür.

### Bağlantılar
- `docs/03-delivery/guides/documentation-workflow.md`
- `docs/01-architecture/01-system/02-frontend-architecture.md`
