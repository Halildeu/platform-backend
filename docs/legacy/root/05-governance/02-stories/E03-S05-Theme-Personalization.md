# Story E03-S05 – Theme Personalization & Custom Themes

- Epic: E03 – Theme & Layout  
- Story Priority: 130  
- Tarih: 2025-12-07  
- Durum: Ready  
- Modüller / Servisler: frontend-shell, ui-kit, variant-service, api-gateway

## 1. Kısa Tanım

Kullanıcılar en fazla 3 kişisel tema oluşturup yönetebilir; admin’ler global temaları düzenleyip yayınlar. Tüm temalar tek kaynak (figma.tokens.json → CSS var) ve yeni renk seçici/registry üzerinden yönetilir; kullanıcı global temalara dokunamaz, sadece kopyalayıp özelleştirir.

## 2. İş Değeri

- Kurumsal görünüm tek yerde yönetilir, hatalı renk/kontrast riskini düşürür.  
- Kullanıcılar kendi tercihlerini (örn. yüzey tonu, accent) kalıcı yapabilir; memnuniyet artar.  
- Global–user ayrımı ve 3 tema limitiyle temaların kontrolsüz çoğalması engellenir.  
- ERP aksiyon dili (primary/secondary/danger) korunur; güvenlik ve tutarlılık bozulmaz.

## 3. Bağlantılar (Traceability Links)

- SPEC: docs/05-governance/06-specs/SPEC-E03-S05-THEME-PERSONALIZATION-V1.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S05-Theme-Personalization.acceptance.md  
- ADR: docs/05-governance/05-adr/ADR-016-theme-layout-system.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md

## 4. Kapsam (Scope)

### In Scope
- Global tema registry ve admin publish akışı (read-only for users).  
- User temaları (max 3) için CRUD, base global’den fork, override whitelist.  
- Theme registry tabanlı picker alanları (surface/text/border/accent/elevation/overlay).  
- Tema seçimi ve persist (profile + localStorage), resolved tema endpoint tasarımı.  
- Tema kartlarında mini önizleme (bg layout, panel, accent şerit, overlay şeridi).

### Out of Scope
- Yeni görsel tasarım üretimi (Figma tarafı).  
- Temaların organizasyon bazlı paylaşımı.  
- Aşırı detaylı a11y kontrast otomasyonu (uyarı yeterli, otomatik düzeltme yok).

## 4.1 Registry Formatı (bilgilendirme)

```ts
ThemeRegistryItem {
  id: string;                  // örn: "surface.panel.bg"
  label: string;               // "Panel Arka Planı"
  group: "surface" | "text" | "border" | "accent" | "overlay" | "radius" | "motion";
  type: "color" | "opacity" | "radius" | "motion";
  cssVars: string[];           // ["--surface-panel-bg"]
  editableBy: "USER_ALLOWED" | "ADMIN_ONLY";
  description?: string;
}
```

UI editörü ve variant-service içindeki theme bounded context aynı sözleşmeyi kullanacak; picker yalnız bu registry öğeleri üzerinden çalışır.

## 4.2 Fork Davranışı

- “Kopyala ve özelleştir” → yeni tema `type=USER`, `baseThemeId=seçili global`, `overrides={}` (boş).  
- Global tema güncellenirse user tema base’den güncellenmiş değerleri alır, kendi overrides’ı kalır; global kayıt değişmez.

## 4.3 Registry (editör için başlangıç tablosu)

| id                    | label                     | group    | type   | cssVars                              | editableBy    |
|-----------------------|---------------------------|----------|--------|--------------------------------------|---------------|
| surface.default.bg    | Sayfa zemini              | surface  | color  | --surface-default-bg                 | USER_ALLOWED  |
| surface.panel.bg      | Panel/kart zemini        | surface  | color  | --surface-panel-bg                   | USER_ALLOWED  |
| surface.modal.bg      | Modal/drawer zemini       | surface  | color  | --surface-modal-bg, --surface-drawer-bg | USER_ALLOWED  |
| surface.muted.bg      | Muted yüzey (rozet/ikon)  | surface  | color  | --surface-muted-bg                   | USER_ALLOWED  |
| surface.overlay.bg    | Overlay arka planı        | surface  | color  | --surface-overlay-bg                 | USER_ALLOWED  |
| surface.header.bg     | Header zemini             | surface  | color  | --surface-header-bg                  | USER_ALLOWED  |
| text.primary          | Metin (primary)           | text     | color  | --text-primary                       | USER_ALLOWED  |
| text.secondary        | Metin (secondary)         | text     | color  | --text-secondary                     | USER_ALLOWED  |
| text.inverse          | Metin (inverse)           | text     | color  | --text-inverse                       | USER_ALLOWED  |
| border.subtle         | Çizgi (subtle)            | border   | color  | --border-subtle                      | USER_ALLOWED  |
| border.default        | Çizgi (default)           | border   | color  | --border-default                     | USER_ALLOWED  |
| border.bold           | Çizgi (bold)              | border   | color  | --border-bold                        | ADMIN_ONLY    |
| accent.primary        | Accent birincil           | accent   | color  | --accent-primary                     | USER_ALLOWED  |
| accent.primary-hover  | Accent hover              | accent   | color  | --accent-primary-hover               | USER_ALLOWED  |
| accent.focus          | Focus/outline             | accent   | color  | --accent-focus                       | USER_ALLOWED  |
| action.danger         | ERP Danger (admin)        | accent   | color  | --action-danger-bg, --action-danger-text | ADMIN_ONLY    |

Not: Registry genişleyebilir; USER_ALLOWED alanlar picker’da açılır, ADMIN_ONLY alanlar yalnız admin’e görünür.

## 5. Task Flow (Ready → InProgress → Review → Done)

```text
+--------------+-------------------------------------------------------------+------------+-------------+---------+------+
| Modül/Servis | Task                                                        | Ready      | InProgress  | Review  | Done |
+--------------+-------------------------------------------------------------+------------+-------------+---------+------+
| frontend     | Tema kartı mini preview + 3/3 kişisel tema sınırı UI        | 2025-12-07 |             |         |      |
| frontend     | Theme registry tabanlı picker (admin/user mod, whitelist)   | 2025-12-07 |             |         |      |
| backend      | Tema model/endpoint tasarımı (global publish, user CRUD)    | 2025-12-07 |             |         |      |
| backend      | Resolved theme endpoint (/me/theme/resolved) kontratı       | 2025-12-07 |             |         |      |
| qa           | Acceptance & DoD senaryoları, kontrast/limit testleri       | 2025-12-07 |             |         |      |
+--------------+-------------------------------------------------------------+------------+-------------+---------+------+
```

## 6. Fonksiyonel Gereksinimler (Özet)

- GLOBAL tema CRUD/publish yalnız admin; kullanıcı sadece seçer veya fork eder.  
- Kullanıcı başına en fazla 3 USER tema; limit UI + API tarafında doğrulanır.  
- Registry’deki whitelist alanlar için renk seçici; token dışı ham renk kullanımına izin yok.  
- Tema seçimi persist edilir (profil + local); oturum açınca resolved tema uygulanır.  
- Tema kartları mini önizleme ile tıklamadan fikir verir.

## 7. Non-Fonksiyonel Gereksinimler

- Tek kaynak: figma.tokens.json → generator → CSS var; override’lar sadece var set eder.  
- Güvenlik: ERP action/danger renkleri user override’ına kapalı.  
- A11y: Kontrast uyarısı (min AA), focus ring her temada görünür.  
- Performans: Tema switch yalnız CSS var güncellemesi; render döngüsü yok.  
- Test: lint:semantic yeşil; picker/limit/resolved tema akışı için UI + API testleri.

## 8. Acceptance ve DoD

- Acceptance: bkz. docs/05-governance/07-acceptance/E03-S05-Theme-Personalization.acceptance.md  
- DoD (özet): lint/format; semantic var kullanımı; limit ve yetki testleri; contrast uyarısı; doküman linkleri güncel; smoke test (global tema seçimi, user tema create/edit/delete, picker override, persist).
