---
title: "Reports Route Guard & Deep-Link Contract"
status: in_review
owner: "@team/frontend"
workflow_tickets:
  - FE-03
  - QA-01
last_review: 2025-11-12
---

## 1. Kapsam
- Shell uygulamasındaki `/reports/*` ve `/admin/reports/*` yolları Reporting MFE’sine (remote: `mfe_reporting/ReportingApp`) yönlenir.
- Guard zinciri `ProtectedRoute` + izin kontrolü (`PERMISSIONS.REPORTING_MODULE = VIEW_REPORTS`) ile yönetilir.
- Deep-link sözleşmesi, Users MFE başta olmak üzere diğer modüllerin rapor ekranlarını parametrelerle açarken uyacağı kuralları tarif eder.

## 2. Guard Akışı
1. **Auth kontrolü** — `token` yoksa `/login`’a yönlendirilir.
2. **Permission kontrolü** — `hasPermission(['VIEW_REPORTS'])` başarısız ise `/unauthorized` sayfasına `state.from = <path>` bilgisiyle yönlendirilir.
3. **Canonical render** — İzin geçtiyse remote ReportingApp yüklenir.
4. **Telemetry** — RouteTracker `telemetryClient.trackPageView` ile path’i loglar (`/reports/users`, `/admin/reports/users`, …).

| Path | Alias | Modül | Gerekli İzin | Not |
| --- | --- | --- | --- | --- |
| `/reports/*` | `/admin/reports/*` | Reporting | `VIEW_REPORTS` | `/admin/reports` → `DEFAULT_REPORTS_PATH = /admin/reports/users` |
| `/reports` | `/admin/reports` | Reporting | `VIEW_REPORTS` | Kök istekler otomatik `/admin/reports/users`’a yönlenir |

> Alias yönlendirmeleri (`<Route path="/reports" element={<Navigate to="/reports/users" />}>`) shell tarafında tutulur; remote içindeki router yalnızca `/reports/*` bekler.

## 3. Deep-Link Sözleşmesi
- Canonical URL şablonu: `/admin/reports/<module>?<query>`
- Şu an aktif tek modül: `users`. Gelecek modüller `mfe-reporting/src/modules/*.ts` dosyaları altında tanımlanır ve nav kaydına `id`, `navKey`, `breadcrumbKeys` ekler.
- Desteklenen query parametreleri:
  - `userId` — Users MFE’den seçili kullanıcıyı taşıyan ID; raporda detay panelini otomatik açmak için kullanılacak (TODO: FE-03 implementasyonu).
  - `search` — Users gridindeki arama metnini rapor filtresine taşır.
  - `variant` — Rapor varyant yöneticisinde belirli bir view’i seçmek için (Reporting `ReportPage` içinde uygulanmış durumda).
  - Ortak parametreler (örn. `advFilter`) Reporting MFE’nin kendisine aittir; shell yalnızca query’yi pass-through eder.
- Kaynak modüller:
  - **UsersPage** (`apps/mfe-users/src/pages/users/UsersPage.ui.tsx`) → “Kullanıcı Raporu” butonu `userId` veya `search` paramı ile `/admin/reports/users`’a yönlendirir.
  - Diğer modüller (Access, Audit) henüz deep-link üretmiyor; FE-03 kapsamında nav aksiyonu ve query sözleşmesi genişletilecek.

### Beklenen Güncellemeler (FE-03)
1. Reporting nav içinde modül → izin haritasını `mfe-shell/src/app/ShellApp.ui.tsx` ve `resolve-menu-selection` util’lerinde belgeye uygun hale getir.
2. Users → Reports CTA’sında `userId` / `search` / `status` query paramları rapor filtrelerine bağlanacak (Reporting `module.createInitialFilters` + `createHref` API’si güncellendi).
3. `/admin/reports/<module>` için canonical list (`reports/users`, `reports/audit`, ...) `docs/frontend/docs/03-routing/??` altına da işlenmeli.

## 4. Doğrulama Senaryoları
- Cypress: `cypress/e2e/reports-routing.cy.ts`
  - `pnpm --filter frontend cypress run --spec cypress/e2e/reports-routing.cy.ts`
  - Senaryolar: token yok → login, izin yok → unauthorized, izin var → default rapor.
- Manual: Shell menüsünde “Raporlar” girişi yalnızca `VIEW_REPORTS` izniyle görünür (Header menü item’ları `hasPermission` ile filtreleniyor).

## 5. Açık Sorular & Aksiyonlar
- **Query → filtre eşlemesi**: Reporting MFE’nin `module.createInitialFilters()` fonksiyonlarına `userId` ve `search` binding’i eklenmeli. Bu çalışma FE-03 uygulama aşaması.
- **Deep-link audit**: `/admin/users` → `/admin/reports/users?userId=...` yönlendirmesi için telemetry event’i (örn. `reports.deep_link`) eklenmeli mi? Ops karar bekleniyor.
- **Yeni modüller**: `/admin/reports/<module>` listesine yeni kayıt eklerken:
  1. `docs/03-delivery/guides/reports-routing-contract.md` içindeki path tablosuna satır ekleyin.
  2. Shell menüsünde izin kontrolünü güncelleyin (`PERMISSIONS` sabitine yeni anahtar ekleyin).
  3. Reporting modül dosyasında (`apps/mfe-reporting/src/modules/<modul>/index.tsx`) `createInitialFilters(params)` imzasını takip edin; gerekli query paramlarını whitelist’e alın.
  4. Guard regressions: Cypress senaryosu ve CI workflow’una yeni modülün deep-link testini ekleyin (`cypress/e2e/reports-*.cy.ts`).

Bu sözleşme uygulanana kadar FE-03 kartı “planned” statüsünde kalacaktır. Implementasyon bittiğinde `Board.json` ve ilgili dökümanlar “done” olarak güncellenecek.
