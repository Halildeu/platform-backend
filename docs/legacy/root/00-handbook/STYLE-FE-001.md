---
title: "STYLE-FE-001 – Frontend Kod Yazım Rehberi"
status: published
owner: "@team/frontend"
last_review: 2025-11-19
tags: ["style", "frontend", "quality"]
---

# STYLE-FE-001  
**Başlık:** Frontend Kod Yazım Rehberi  
**Versiyon:** v1.1  
**Tarih:** 2025-11-18  
**Kapsam:** ERP Web Frontend (React + TypeScript, MFE monorepo)  

> Bu doküman frontend kod stili için **kanonik kaynaktır**; tüm özetler ve referanslar buraya işaret eder.
> İlgili dokümanlar:  
> - Backend stil rehberi: `docs/00-handbook/STYLE-BE-001.md`  
> - Backend mimarisi ve sözleşme özeti: `docs/01-architecture/01-system/01-backend-architecture.md`  
> - Frontend monorepo yapısı: `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`)

---

## 1. Amaç

Bu doküman, frontend geliştirmelerinde:

- okunabilir,  
- tutarlı,  
- test edilebilir,  
- sürdürülebilir  

kod üretilmesi için uyulması gereken standartları tanımlar.  
Tüm web frontend projeleri (özellikle `frontend/` monorepo altındaki MFE’ler) bu rehbere uymalıdır.

---

## 2. Temel İlkeler

- **Basitlik:** Gereksiz karmaşadan kaçınılır.  
- **Tek sorumluluk:** Her component tek iş yapar; büyük sayfalar bile alt bileşenlere bölünür.  
- **Okunabilirlik > Kısalık:** Anlaşılır kod, kısa koddan daha değerlidir.  
- **DRY:** Aynı UI/mantık/stil tekrar yazılmaz, ortak component/util’e taşınır.  
- **Tip güvenliği:** React projelerinde TypeScript zorunludur; `any` sadece istisnai durumlarda kullanılabilir.  

---

## 3. Component Mimarisi

### 3.1 Component Tipleri

1. **Page / Route Component**
   - Route’a bağlı ekranlar (`/admin/users`, `/reports/users` vb.).  
   - Data fetch, global state bağlantısı ve side-effect yönetimi burada olur.  
   - Alt component’leri orkestre eder; UI detayları alt bileşenlere bırakılır.

2. **Presentational / UI Component (`*.ui.tsx`)**
   - Sadece görünüm ve minimal state.  
   - Props alır, callback/event ile dışarı haber verir.  
   - İş kuralı içermez; mümkün olduğunca saf fonksiyonel bileşenlerdir.

3. **Form / Control Component**
   - Form alanlarını, validation mesajlarını ve layout’u kapsar.  
   - Validation mesajları backend ACCEPTANCE ile uyumlu olmalıdır.

**Kural:**  
- API çağrıları + karmaşık iş kuralları → Page/Container veya `entities/*/api` / `features/*/model` katmanında.  
- UI component “nasıl göründüğünü” bilir, “işi” bilmez.

---

## 4. İsimlendirme Kuralları

İsimlendirme için genel rehber: `docs/00-handbook/NAMING.md`. Özet:

### 4.1 Dosya / Component

- React:
  - Dosya: `UsersPage.ui.tsx`, `UsersGrid.ui.tsx`, `LoginPopover.ui.tsx`  
  - Component: `UsersPage`, `UsersGrid`, `LoginPopover`

### 4.2 Değişken ve Fonksiyonlar

- `camelCase` → `userId`, `isLoading`, `handleSubmit`  
- Boolean’lar anlamlı:
  - `isLoading`, `hasError`, `isDisabled`  

### 4.3 CSS / Class İsimleri

- Utility-first (Tailwind) kullanılır; proje içinde belirlenen token/utility setine sadık kalınır.  
- Ant Design veya başka UI kütüphanesiyle gelen class isimleri yalnız adapter katmanında kalmalıdır.

---

## 5. Klasör Yapısı (MFE Monorepo)

Detaylı monorepo yapısı için:  
- `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md` (kanonik detay: `frontend/docs/architecture/frontend/frontend-project-layout.md`)

Özet:

```text
frontend/
  apps/
    mfe-shell/
    mfe-reporting/
    mfe-users/
    mfe-access/
    mfe-audit/
  packages/
    ui-kit/
    shared-types/
    i18n-dicts/
```

Her MFE için iç yapı:

```text
apps/<mfe>/src/
  app/            # Router, Theme/i18n Provider, ShellServices entegrasyonu
  manifest/       # Sayfa manifestleri + Zod/JSON schema
  i18n/           # Locale çözümü, sözlük yükleme/cache
  shared/         # Atomik UI, küçük yardımcılar (domain agnostik)
  entities/<entity>/
    model/        # tipler, state, selector
    api/          # entity’ye ait HTTP/query sözleşmeleri
    lib/          # iş kuralı parçaları
    ui/           # küçük görünümler
  features/<feature>/
    ui/           # feature odaklı bileşenler
    model/        # küçük orkestrasyon, hook
    lib/
  widgets/<widget>/   # sayfalar arasında paylaşılan kompozit bloklar
  pages/<route>/
    index.tsx     # manifest + PageLayout kompozisyonu, routing
    manifest/     # sayfaya özel manifest dosyaları
  process/        # (opsiyonel) Çok adımlı akışlar (wizard, onay)
```

**Kompozisyon yönü:** `pages` → `widgets/features` → `entities` → `shared`.  
Ters bağımlılık yasaktır (ESLint boundaries ile enforce edilir).

---

## 6. State Yönetimi

- Local state:
  - Sekme durumu, modal açık/kapalı, form değerleri vb.  
- Global state:
  - Auth, kullanıcı profili, tema, dil, uygulama seviyesi filtreler vb.

Önerilen:

- React: Redux Toolkit / Zustand / Context + custom hooks  

**Kural:**

- Her şeyi global state’e atmak yasaktır.  
- Side-effect (API çağrısı, localStorage) mümkünse hook/service içinde yapılır; UI component sadece sonucu tüketir.

---

## 7. i18n ve API Entegrasyon Kuralları

### 7.1 i18n (Metin ve Sözlük Kullanımı)

- Hard-coded metin yoktur; tüm kullanıcıya dönük metinler `@mfe/i18n-dicts` içindeki uygun namespace altında tutulur (`common.*`, `users.*`, `access.*` vb.).  
- Her MFE kendi i18n hook’unu kullanır:
  - Shell/common alanı: `useShellCommonI18n` + `common` namespace’i  
  - Users alanı: `useUsersI18n` + `users` namespace’i  
  - Diğer alanlar için de benzer `use<Domain>I18n` hook’ları tercih edilir.  
- Grid/toolbar/filtre gibi bileşenlerde görünen tüm metinler (kolon başlıkları, panel başlıkları, buton metinleri, filtre etiketleri) i18n üzerinden beslenmelidir.  
- Dil değişiminde:
  - `useMemo` / `useCallback` bağımlılık listelerine `t` ve/veya `locale` eklenmeli,  
  - ColumnDefs, `localeText` vb. yapıların metinleri yeniden hesaplanmalıdır.  
- Yeni metin eklerken:
  - İlgili Story’nin i18n acceptance maddeleri (E07-S01 veya ilgili Story) gözden geçirilir; pseudo-locale/a11y gereksinimleri dikkate alınır.

### 7.2 Service Katmanı Zorunluluğu

- UI component’ler doğrudan `fetch/axios` çağırmaz.  
- Tüm API erişimi:

```text
apps/<mfe>/src/entities/<entity>/api/*.ts
apps/<mfe>/src/features/<feature>/model/*.api.ts (gerekirse)
```

üzerinden yapılır; response mapping bu seviyede tamamlanır.  
- **Kontrat kaynağı:** Service katmanı her zaman `STYLE-API-001` ve ilgili `docs/03-delivery/api/*.api.md` kontratlarına göre yazılır; parametre isimleri/zarf yapısı/hata şeması burada tanımlıdır.

### 7.3 API Zarf Formatı

- Gerçek response formatı ilgili `docs/03-delivery/api/*.api.md` dokümanlarında tanımlıdır (`users.api.md`, `auth.api.md` vb.).  
- FE tarafında service katmanı:
  - zarf yapısını (`items/total/page/pageSize` vb.) okur,
  - component’lere sade model döner.

```ts
type PagedResult<T> = {
  items: T[];
  page: number;
  pageSize: number;
  total: number;
};

### 7.4 Auth & Shared HTTP Kullanım Kuralı

- Shell (`mfe-shell`) sistemin tek “Auth Authority” bileşenidir. Keycloak login, silent-check-sso, refresh ve session management yalnız shell’de çalışır.  
- Login sonrası oluşan `accessToken`, `refreshToken`, `idToken` ve `profile` değerleri BroadcastChannel aracılığıyla tüm tarayıcı sekmelerine push edilir; BroadcastChannel desteklenmiyorsa logout sinyali `storage` eventi ile tetiklenir ve token hiçbir kalıcı depoda tutulmaz. Her MFE açıldığında auth durumu yalnız shell-services üzerinden okunur.
- **Kural:** Hiçbir remote MFE doğrudan Keycloak login ekranına yönlendirme yapamaz, kendi Keycloak client’ını başlatamaz ve custom interceptor tanımlayamaz. Tüm HTTP çağrıları `@mfe/shared-http` paketindeki tek axios instance üzerinden yapılmalı, token enjeksiyonu bu katman tarafından gerçekleştirilmelidir.  
- Bağımsız MFE login akışı veya custom axios/fetch kullanımı anti-pattern’dir; kod incelemelerinde reddedilir.
```

Component bu zarf yapısıyla uğraşmaz.

### 7.4 Pagination & Filter Parametreleri

- Tüm liste endpoint’lerinde `page`, `pageSize`, `sort`, `search/advancedFilter` parametreleri backend sözleşmesine uygun kullanılır.  
- Frontend tarafında farklı parametre isimleri türetmek yasaktır; mapping yalnız service katmanında yapılır.

### 7.5 Error Handling

- API error formatı backend ile hizalıdır (`error`, `message`, `fieldErrors`, `meta` gibi alanlar).  
- Service katmanı HTTP koduna göre genel hata tipi belirler; `fieldErrors` varsa formlara alan bazlı hata olarak geri verir.  
- UI component’in işi:
  - Genel mesajı göstermek,  
  - Alanlara özel mesajları bağlamak.

---

## 8. Form ve Validasyon

- React: Controlled forms (React Hook Form / benzeri bir kütüphane tercih edilebilir).  

**Kural:**

- Validation kuralları SPEC ve ACCEPTANCE ile uyumlu olmak zorunda.  
- Backend daha sıkı kural uyguluyorsa, frontend de backend’e uyacak şekilde güncellenir (frontend kafasına göre validation kuralı uydurmaz).

---

## 9. Stil / CSS Kuralları

- Tasarım sistemi / design tokens varsa:
  - Renk, font, spacing, radius vb. her şey token üzerinden yönetilir.  
- Ortak UI pattern’leri (PageLayout, Card, Modal, DataGrid vb.) `packages/ui-kit` veya app içi `shared/ui` altında toplanmalıdır.  
- Kritik ekranlar için responsive kontrol yapılmalıdır; breakpoint’ler proje içinde sabitlenmelidir (`sm`, `md`, `lg`, `xl` vb.).  

---

## 10. Erişilebilirlik (Accessibility)

- Semantik HTML:
  - `button`, `a`, `nav`, `main`, `header`, `footer` vb. elemanlar yerinde kullanılır.  
- Form alanları:
  - `label` + `htmlFor` veya uygun React yapıları ile bağlanır.  
- İkon-only butonlarda:
  - `aria-label` veya yanında metin olması zorunludur.  
- Focus:
  - `outline: none` kullanılıyorsa, mutlaka alternatif focus stili verilmelidir.  

---

## 11. Hata & Mesaj Yönetimi

- Global error handler (toast/snackbar veya shell `app:toast` bus) bulunmalıdır.  
- Kullanıcıya teknik stack trace gösterilmez.  
- Mesajlar sade, tek cümlelik, kullanıcı dilindedir:

```text
✅ “Sunucuda bir sorun oluştu. Lütfen tekrar deneyin.”
❌ “NullPointerException at ServiceImpl:42”
```

---

## 12. Test Kuralları (Frontend)

- Kritik component’ler için en az bir render/interaction testi yazılması hedeflenir.  
- API entegrasyonları:
  - React: Testing Library + MSW / jest mocks  

Örnek test isimleri:

```ts
it('should disable submit button when form is invalid', () => { /* ... */ });
it('should show error message when login fails', () => { /* ... */ });
```

---

## 13. Performans & UX Kuralları

- Büyük listeler için virtual scroll / lazy load tercih edilir (mfe-reporting/mfe-users grid’leri).  
- API çağrısı sırasında:
  - Loading state gösterilir,  
  - Submit butonu devre dışı bırakılır (double-click engellenir).  
- Gereksiz re-render:
  - React: `memo`, `useMemo`, `useCallback` gerektiği kadar; aşırı kullanımdan kaçınılır.  

---

## 14. Lint & Format

- ESLint + Prettier (veya eşdeğer) zorunludur.  
- `npm run lint` CI pipeline’da koşar; lint hatalı kod merge edilemez.  
- Ortak kurallar `frontend/packages/config` altındaki ESLint/TSConfig ayarlarından gelir.

---

## 15. Kod ve Dosya Boyutu Sınırları

FRONTEND monorepo kalite limitleri (bkz. `docs/00-handbook/FRONTEND-PROJECT-LAYOUT.md`):

- Bir UI component dosyası idealde 250 satırı geçmemelidir.  
- Hook/service dosyası 200 satırı geçmemelidir.  
- Tek fonksiyon 40 satırı geçiyorsa parçalamak tercih edilmelidir.  
- JSX/HTML template kısmı 150 satırı geçiyorsa component bölünmelidir.  

Kural aşımlarında build/lint fail; dosya parçalama veya pattern uygulaması beklenir.

---

## 16. Definition of Quality (DONE Kriteri – FE)

Bir frontend geliştirme DONE sayılabilmesi için:

- İlgili SPEC ile uyumlu,  
- Tüm ACCEPTANCE kriterlerini karşılamış,  
- Varsa ilgili ADR kararlarını uygulamış,  
- Bu STYLE-FE-001 kurallarına **ve** monorepo yapısına (`frontend-project-layout.md`) uymuş,  
- Lint + testler temiz,  
- UX akışı (loading, empty state, error) düşünülmüş,  
- Gerekli ekranlar için responsive ve erişilebilirlik kontrolleri yapılmış,  
- `PROJECT_FLOW.md` üzerinde ilgili STORY “Done” durumuna alınmış olmalıdır.

---

## 17. Notlar

Bu doküman, tüm frontend projeleri için **tek resmi stil ve kalite standardıdır**.  
Backend ve API tarafı için sırasıyla:

- `STYLE-BE-001.md`  
- API sözleşmeleri: `docs/03-delivery/api/*.md`  

referans alınmalıdır.  
İhtiyaçlar değiştikçe versiyon numarası artırılarak güncellenecektir.
