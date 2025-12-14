# Story E03-S01 – Theme & Layout System v1.0 (Tailwind + Figma Tokens)

- Epic: E03 – Theme & Shell Layout  
- Story Priority: 310  
- Tarih: YYYY-AA-GG  
- Durum: Done

## Kısa Tanım

ADR-016’da tanımlanan tema ve layout modelini kod tabanına uygulamak: Figma token’larından CSS var’lara, oradan Tailwind config ve UI Kit/Shell bileşenlerine uzanan tek yönetişim zinciri kurmak; Ant Design bağımlılıklarını kademeli olarak Tailwind primitives’e taşımak.

## İş Değeri

- Tasarım ekibi (Figma) ve frontend (Tailwind/UI Kit) aynı tema/komponent sözlüğünü kullanır; drift azalır.
- Tema değişiklikleri (gündüz/gece/high-contrast) kodu değiştirmeden, yalnız token ve CSS var seviyesinde yönetilebilir.
- Ant Design bağımlılıkları temizlenir; Shell ve MFE’ler Tailwind-only bir UI katmanına kavuşur.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`  
- ADR: ADR-016 (Theme & Layout System v1.0), ADR-005/ADR-015 (Grid teması ile etkileşimli ekranlar)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`
 - FEATURE REQUEST: `docs/05-governance/FEATURE_REQUESTS.md` (FR-005 – Theme & Shell Layout Figma Kit v1.0)  

## Kapsam

### In Scope
- Tema modelinin kökte uygulanması:
  - `data-theme`, `data-mode` ve gerekirse diğer eksenlerin (density, radius, elevation, motion) HTML kökünde yönetilmesi.
- Semantic token katmanı:
  - Figma değişken isimleri ↔ CSS var isimleri (`--surface-app`, `--surface-sidebar`, `--text-primary`, `--accent-default` vb.) eşlemesi.
  - `theme.css` veya benzeri bir CSS dosyasında bu var’ların tanımlanması.
- Tailwind entegrasyonu:
  - Tailwind config’in yalnız `var(--token)` referanslarını bilmesi; doğrudan hex/Ant tokenı içermemesi.
  - Semantic renklerin `theme.extend.colors` altına haritalanması (örn. `app.bg`, `sidebar.bg`, `text.primary`).
- UI Katmanı:
  - UI Kit primitives (`Button`, `Badge`, `Tooltip`, vb.) ve Shell layout bileşenlerinin (Topbar, Sidebar, PageLayout) bu token’lar üzerinden boyanması.
  - Shell auth ekranlarının (login/register/unauthorized) ve header bileşenlerinin Tailwind + UI Kit primitives’ine taşınması (E01-S05 ile kısmen yapılmış durumda).
- Ant Design kaldırma planı:
  - `legacy-ant-design-tailwind-mapping.md` rehberinden yola çıkarak Ant Design bağımlılıklarını kademeli olarak kaldırmak.

### Out of Scope
- Figma tarafındaki değişken setlerinin detaylı kurulumu (bu Story uygulama tarafına odaklıdır; Figma ekibi kendi rehberine göre çalışır).
- MFE’lerdeki tüm ekranların tek akışta eksiksiz tema/a11y doğrulaması (bu Story’nin ötesinde kademeli yapılır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| HTML kökünde tema/mode/density veri özniteliklerinin eklenmesi| 2025-11-18   | 2025-11-18    | 2025-11-19   | 2025-11-19  |
| Figma token’larından CSS var semantic token katmanının kurulması| 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Tailwind config’in yalnız var(--token) referanslarıyla güncellenmesi| 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| UI Kit primitives’in semantic token’lara taşınması          | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Shell layout bileşenlerinin yeni tema sistemine uyarlanması | 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
| Ant Design bağımlılıklarının kaldırılması için ilk temizlik dalgası| 2025-11-19   | 2025-11-19    | 2025-11-19   | 2025-11-19  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. HTML kökünde tema/mode/density vb. eksenler veri öznitelikleri olarak yönetilmeli; tema değiştirici yalnız bu öznitelikleri değiştirmelidir.
2. Tüm yeni UI bileşenleri Tailwind + semantic token’lar üzerinden stil almalı; ham hex veya Ant token referansı kullanılmamalıdır.
3. UI Kit primitives ve Shell layout bileşenleri, seçili tema profiline göre görünümünü değiştirebilmeli (ör. sidebar variant, app background, text rengi).
4. Ant Design bileşenleri (özellikle Shell/Shell auth/Access/Reporting grid toolbar’ları) için kademeli kaldırma planı tanımlanmalı ve takip edilmelidir.

## Non-Functional Requirements

- Tema değişiminde minimal reflow; mümkün olduğunca yalnız boyama (`color`, `background`, `border`) değişmelidir.
- High-contrast modunda ana grid ve form ekranlarında AAA kontrast sağlanmalıdır.
- Tema testleri otomatik hale getirilmelidir (ör. appearance × density kombinasyonları için snapshot/görsel test).

## İş Kuralları / Senaryolar

- “Yeni tema profili eklenmesi” → Figma token seti + CSS var + Tailwind config + layout profilleri güncellenir; sayfa kodu değiştirilmez.
- “Ant bağımlılığı görüldü” → `legacy-ant-design-tailwind-mapping.md` rehberine göre uygun Tailwind primitive ile değiştirilir; referans bu Story altında ticket olarak takip edilir.

## Interfaces (API / DB / Event)

- API/DB ile doğrudan yeni arayüz yok; tema değişimleri, mevcut UI bileşenlerinin görünüm katmanında kalmalıdır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`

## Definition of Done

- [ ] Tema/mode/density vb. eksenler için acceptance checklist’i sağlanmış olmalı.  
- [x] Tema/mode/density vb. eksenler için acceptance checklist’i sağlanmış olmalı.  
- [x] Acceptance dosyasındaki maddeler (`docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md`) tam karşılanmış olmalı.  
- [x] ADR-016’daki theme/layout kararı uygulanmış ve eski Ant bağımlılıkları kaldırılmış olmalı.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [x] Kod review’dan onay almış olmalı.  
- [x] İlgili testler (Storybook/Chromatic, e2e, a11y) yeşil olmalı.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- High-contrast ve density kombinasyonları için gelecekte ek görsel/a11y testleri eklenebilir.

## Dependencies

- ADR-016 – Theme & Layout System v1.0.
- ADR-005 / ADR-015 (grid teması ile etkileşimde olan ekranlar için).

## Risks

- Tema değişimindeki regressions fark edilmeden production’a gitmesi; özellikle high-contrast modda okunabilirlik kaybı.
- Tailwind config ve CSS var seti arasında drift; tema değişikliklerinin bazı bileşenlere yansımaması.

## Flow / Iteration Notu

Bu Story, ilk planlamada Sprint 02 altında düşünülmüştü; yeni çalışma şeklinde tüm izleme `docs/05-governance/PROJECT_FLOW.md` üzerindeki akışla yapılır. Tema/layout ve Ant temizliğiyle ilgili işler bu akışta `Story: E03-S01` ile işaretlenir.

## İlgili Artefaktlar

- `docs/05-governance/05-adr/ADR-016-theme-layout-system.md`
- `docs/01-architecture/01-system/legacy-ant-design-tailwind-mapping.md`
- `docs/01-architecture/01-system/02-frontend-architecture.md`
