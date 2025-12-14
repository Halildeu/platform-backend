## İsimlendirme Kuralları (FE/BE/Docs)

Amaç
- Tüm ekip için yalın, anlaşılır ve uygulanabilir bir isimlendirme standardı sağlamak.
- PR incelemelerinde ve otomasyonlarda ortak beklentiyi netleştirmek.

Genel
- Dizin adları: kebab-case (ör. `user-management`, `grid-variants`).
- Dosya uzantıları: TypeScript/React için `.ts`/`.tsx`; markdown için `.md`.
- Özel/köşetaşı dosyalar: UPPER_SNAKE (örn. `README.md`, `ADR.md`).

Frontend (React/TS)
- Bileşen dosyaları (UI): PascalCase + `.ui.tsx` (örn. `UsersGrid.ui.tsx`, `UserDetailDrawer.ui.tsx`).
- Sayfa/Widget: PascalCase + `.ui.tsx` (örn. `UsersPage.ui.tsx`).
- Hook: `use` ile başlar, camelCase + `.ts` (örn. `useUsersQuery.ts`).
- API katmanı: domain-odaklı, kebab/camel alan; sonek `.api.ts` (örn. `users.api.ts`).
- Tipler/Modeller: sonek `.types.ts` / `.model.ts` (örn. `user-management.types.ts`).
- Yardımcı/Util: sonek `.util.ts` / `.lib.ts`.
- Test: birim `.spec.ts`/`.spec.tsx`; e2e `.cy.ts` (örn. `users-ssrm-single-fetch.cy.ts`).
- Stiller/tema: kebab-case (örn. `grid-theme.css`).

Backend (Java/Spring)
- Paketler: tamamen küçük harf, ters domain (örn. `com.example.user`).
- Sınıflar: PascalCase ve rol soneki:
  - `XxxController`, `XxxService`, `XxxRepository`, `XxxEntity`, `XxxDto`, `XxxConfig`.
- Yapılandırma dosyaları: `application-<profile>.properties|yml` (örn. `application-local.properties`).
- REST uçları: kebab-case path’ler (örn. `/api/users/all`, `/api/user-detail`).

Dokümantasyon
- Üst düzey/standart: `README.md` (UPPER_SNAKE).
- Konu/detay dokümanları: kebab-case (örn. `ag-grid-ssrm-export-strategy.md`).
- Agent alanı: `docs/00-handbook/*` altında topla (handbook README/NAMING/ADR-özet).
- ADR’ler: `docs/05-governance/05-adr/` klasöründe çoklu dosya modeli.
  - Adlandırma: `ADR-0001-<kısa-baslik>.md` (sıfır dolgulu sıra numarası + kebab kısa başlık)
  - Alternatif (tercih edilmez ama desteklenir): `0001-<kısa-baslik>.md`
  - İçerik: Başlık, Tarih, Durum, Karar, Gerekçe, Sonuçlar, Bağlantılar
  - İndeks: `docs/05-governance/05-adr/README.md` (opsiyonel — ADR’leri listeler)
- SPEC’ler: `docs/05-governance/06-specs/SPEC-<STORY-ID>-<kısa-konu>.md` (Story ID + kebab-case kısa başlık, gerekiyorsa faz/versiyon soneki eklenir; örn. `SPEC-E02-S02-grid-reporting-v1.md`)

MFE ve Paket Adlandırma
- Mikro-frontend isimleri: `mfe-<context>` (örn. `mfe-users`, `mfe-access`).
- Paylaşılan paket/dizinler: kebab-case (örn. `ui-kit`, `shared-types`).

Sapmalar ve Geçiş
- `backend/backend/docs/*` gibi yinelenen kökler oluşturmayın; tüm dokümanları `docs/*` altında toplayın.
- Mevcut dosyaları yeniden adlandırma planlı ve küçük PR’larla (1:1 eşleme) yapılır; kırık bağ vermemek için değişiklik listesi PR açıklamasına eklenir.

Commit ve Kart Bağlantısı
- Kart numarası öneki: `FE-01: sortModel API eşlemesi` gibi (alan kodu + sıra).
