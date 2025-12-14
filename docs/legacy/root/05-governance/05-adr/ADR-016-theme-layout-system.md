# ADR-016 – Theme & Layout System v1.0

**Durum (Status):** Accepted  
**Tarih:** 2025-11-15  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`
- STORY: E03-S01-Theme-Layout-System.md
- İlgili ADR’ler: ADR-005, ADR-015
- STYLE GUIDE: (Varsa STYLE-FE-001 / STYLE-UI-001)
 - FEATURE REQUEST: `docs/05-governance/FEATURE_REQUESTS.md` (FR-005 – Theme & Shell Layout Figma Kit v1.0)

---

# 1. Bağlam (Context)

- Serban ERP kabuğu farklı sektör profilleri (kurumsal, saha, veri merkezi) ve **gündüz/gece** modları ile çalışmak zorunda. Mevcut React + Ant Design + Tailwind hibrit yaklaşımı, komponentlerin bir kısmının Ant Design tokenlarına bir kısmının Tailwind utility’lerine bağlı kalmasına neden oldu; tema değişiklikleri parçalı uygulanıyor.
- Figma kitindeki token/komponent yapısı ile kod tarafındaki Tailwind/Ant Design eşlemeleri tutarlı değil; paylaşılmış tek karar kaydı bulunmuyordu.
- Shell bileşenleri (Sidebar/Topbar/Page Header) tema profilini davranış olarak yansıtamıyor; her varyant manuel değiştiriliyor.

---

# 2. Karar (Decision)

1. **Tema Modeli**
   - HTML kökünde `data-theme="corporate|field|datacenter|neutral|highContrast"` ve `data-mode="day|night"` öznitelikleri tutulur.
   - Tüm stil/ davranış kararları bu kombinasyona göre hesaplanır; tema butonu yalnızca bu öznitelikleri değiştirir.

2. **Semantic Token Katmanı (Figma & CSS)**
   - Figma’da semantic token koleksiyonları (`surface.app`, `surface.sidebar`, `text.primary`, `accent.default`, `border.subtle`, `shadow.elevated` vb.) tanımlanır; Modes aynı theme/mode eksenleri ile eşleşir.
   - Build çıktısı `design-system/tokens/theme.css` dosyasında bire bir CSS değişkenlerine (örn. `--surface-sidebar`) çevrilir. Token adı ↔ CSS değişken adı bire birdir.

3. **Tailwind Entegrasyonu**
   - `design-system/tailwind/tailwind.config.js` sadece `var(--token)` referanslarını bilir; hex veya Ant Design tokenı içermesi yasaktır.
   - Semantic renkler Tailwind `theme.extend.colors` altına haritalanır:
     ```ts
     colors: {
       app: { bg: "var(--surface-app)" },
       sidebar: { bg: "var(--surface-sidebar)", active: "var(--surface-sidebar-active)" },
       text: { primary: "var(--text-primary)", muted: "var(--text-muted)" },
       accent: { DEFAULT: "var(--accent-default)" },
     }
     ```
   - Komponentler yalnızca Tailwind class’ları kullanır (`bg-sidebar-bg text-text-primary`).

4. **Component Katmanları**
   - **Primitives** (`ui/primitives/Button`, `Input`, `Card`, `Tag`): Tek component → tema sadece görünümü değiştirir; API aynı kalır.
   - **Layout/Shell bileşenleri** (`ui/layout/Sidebar`, `Topbar`, `BottomNav`, `AppShell`): Tema profilini `variant` veya türetilmiş prop üzerinden uygular.
   - **AppShell** temayı `useTheme()` ile alır ve `LAYOUT_PROFILES` tablosundan davranış setini çıkarır (Sidebar yoğunluğu, BottomNav kullanımı, Project Switcher görünürlüğü vb.).

5. **Layout Profilleri**
   ```ts
   type LayoutProfile = {
     sidebarVariant: "dense" | "spacious" | "minimal";
     useBottomNav: boolean;
     showProjectSwitcherInTopbar: boolean;
   };
   const LAYOUT_PROFILES: Record<ThemeId, LayoutProfile> = { ... };
   ```
   - Tema değiştikçe sadece bu tablo güncellenir; sayfa kodu değişmez.

6. **Sidebar API**
   - `variant: "dense" | "spacious" | "minimal"` prop’u bileşenin spacing/typography davranışını belirler; renkler token tabanlıdır.
   - Örnek: `"flex flex-col bg-sidebar-bg text-text-primary " + VARIANT_CLASSES[variant]`.

7. **Repo Yapısı**
   ```
   src/design-system/tokens/theme.css      # Figma tokenlarının CSS var() çıktısı
   src/design-system/tailwind/config.ts    # var(--...) okuyan Tailwind konfigi
   src/ui/primitives/*                     # Button, Input, Card, Tag...
   src/ui/layout/*                         # Sidebar, Topbar, BottomNav, AppShell
   src/app/modules/*                       # Domain modülleri
   ```

8. **Legacy Ant Design Eşlemesi**
   - Mevcut Ant Design temaları için geçiş süreci `docs/01-architecture/01-system/legacy-ant-design-tailwind-mapping.md` dosyasında tutulur.
   - Ant Design tokenları yalnızca bu geçiş dokümanında referans alınır; uygulama kodu Tailwind/semantic tokenlara taşınana kadar rehber görevi görür.

---

# 3. Alternatifler (Alternatives)

- Ant Design temalarını temel alan mevcut yaklaşımı sürdürmek
- Tailwind’i sadece utility katmanı olarak kullanıp tema modelini tanımlamamak

Bu alternatifler tema drift’ini ve bakım maliyetini artırdığı için tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Tasarım ekibi (Figma), frontend (Tailwind), tema runtime (CSS var) ve Shell davranışı tek şemaya bağlanarak çift yönlü drift önlenir.
- `data-theme`/`data-mode` yaklaşımı ile gün/gece ve sektör temaları kod değişmeden değiştirilebilir.
- Primitives ve layout katmanlarının ayrılması UI Kit ile Shell arasında tekrar kullanılabilirliği artırır.
- Ant Design bağımlılıkları ortadan kalkar; UI Kit primitives Tailwind tabanlı hale gelir.

---

# 5. Sonuçlar (Consequences)

- **Ant Design kaldırma planı:** Tüm yeni komponentler Tailwind primitives’e taşınana kadar `legacy-ant-design-tailwind-mapping.md` dosyası kontrol listesi olarak kullanılacak.
- **Build/CI:** Tailwind konfiginde hex veya hard-coded değer tespit edilir ise pipeline kırmızıya çekilecek (`lint-tailwind-theme` adımı).
- **Doküman bağlantıları:** Frontend mimarisi dokümanları ve backlog’da Tailwind tam geçiş kartları `ADR‑0016` referansıyla işaretlenecek.
- **Tema testleri:** Appearance × Density × Radius × Elevation × Motion kombinasyonları için Playwright/Chromatic senaryoları eklenir; high-contrast modu AAA kontrastını sağlamakla yükümlü.
- **Ops etki:** Tema değişiklikleri sadece CSS var güncellemesi olduğundan reflow minimal olacak; performance checklist (ADR eki) bu kararda resmi kayıt kabul edilir.

---

# 6. Tema Runtime Kaynakları

- **Tek doğruluk kaynağı:** `frontend/design-tokens/figma.tokens.json` (raw + semantic tokenlar).
- **Pipeline:** `frontend/scripts/theme/generate-theme-css.mjs` bu dosyayı okuyup `apps/mfe-shell/src/styles/theme.css` dosyasını üretir; CSS değişkenleri yalnız bu script tarafından güncellenir.
- **Tailwind eşleme:** `frontend/tailwind.config.js` semantic token isimlerini `var(--surface-default-bg)` vb. CSS değişkenlerine bağlar; mapping elle düzenlenir fakat değer kaynağı yalnız CSS var’lardır.
- **Kural:** `theme.css` veya component kodunda ham hex/rgb kullanılmaz; değişiklik gerekiyorsa önce Figma tokens güncellenir, ardından `npm run tokens:build` çalıştırılır ve lint/guardrail kontrolleri ile doğrulanır.
- **Lint & Guardrail:** İlk fazda rapor modunda üç kontrol çalıştırılır: `npm run lint:style`, `npm run lint:semantic`, `npm run lint:tailwind`. Bu komutlar semantic token kullanımını doğrular; ileride enforce moduna alınacaktır.

# 7. Uygulama Detayları (Implementation Notes)

- Tailwind konfigürasyonu ve token CSS çıktıları CI’da doğrulanmalıdır.
- Legacy Ant Design bileşenlerinden geçiş için ayrı migration planları hazırlanmalıdır.

---

# 8. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-15  | Accepted | İlk sürüm, ADR-016 kararı kabul edildi |

---

# 8. Notlar

Bağlantılar:
- [Legacy Ant Design → Tailwind Mapping](../../01-architecture/01-system/legacy-ant-design-tailwind-mapping.md)
- [Frontend Architecture](../../01-architecture/01-system/02-frontend-architecture.md)
- [PROJECT_FLOW.md](../PROJECT_FLOW.md)
- [AG Grid SSRM Export Strategy](../../../frontend/docs/ag-grid-ssrm-export-strategy.md)
