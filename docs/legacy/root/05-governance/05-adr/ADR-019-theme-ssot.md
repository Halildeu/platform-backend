# ADR-019 – Theme Single Source of Truth (SSOT)

**Durum (Status):** Proposed  
**Tarih:** 2025-12-06  

**İlgili Dokümanlar (Traceability):**
- EPIC: docs/05-governance/01-epics/E03_ThemeAndLayout.md
- SPEC: docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S01-Theme-Layout-System.acceptance.md
- STORY: docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md
- İlgili ADR’ler: ADR-016-theme-layout-system.md
- STYLE GUIDE: docs/00-handbook/NAMING.md

---

# 1. Bağlam (Context)

- Tema zinciri (Figma → JSON export → generator → theme.css → runtime data-attr → UI var(--…)) dağınık kaynaklar yüzünden tutarsız çalışıyor; Shell’de `data-theme="light/violet/..."` gibi isimler Figma modlarıyla hizalı değil.  
- Overlay min/max/default ve palette renkleri runtime’da sabitlenmiş, figma.tokens.json’da yok; “UI’den değiştirdim ama yansımadı” hatası yaşanıyor.  
- AGENT-CODEX, davranış değişikliklerinde tek kaynak ve traceability zorunlu kılıyor.

# 2. Karar (Decision)

- Tüm tema/appearance/density/radius/elevation/motion/tableSurfaceTone/overlay ve palette bilgisi için **tek kaynak** `frontend/design-tokens/figma.tokens.json` olarak tanımlanır.  
- Root tema/aks durumu yalnız runtime theme-controller tarafından yazılır: `data-theme`, `data-density`, `data-radius`, `data-elevation`, `data-motion`, `data-table-surface-tone`, `data-overlay-intensity`, `data-overlay-opacity`, `data-accent` (palette).  
- Overlay min/max/default ve opacity default değerleri figma.tokens.json’dan okunur; runtime yalnız uygular.  
- Palette (light/violet/emerald/sunset/ocean/graphite) tanımları figma.tokens.json’da tutulur; runtime/UI sabitleri bu kaynağı tüketir, bağımsız tema tanımı barındırmaz; data-accent ile uygulanır.

# 3. Alternatifler (Alternatives)

### 3.1. Mevcut durum (çoklu kaynak)
- Artıları: Hızlı deney yapma kolaylığı.  
- Eksileri: data-theme uyuşmazlıkları, overlay/palette sapmaları, izlenebilirlik zayıf.

### 3.2. SSOT = figma.tokens.json (seçilen)
- Artıları: Tek doğruluk noktası, generator ve runtime’ın senkron çalışması, test edilebilir zincir.  
- Eksileri: Token formatındaki her değişiklik generator/runtimeda güncelleme ister.

# 4. Gerekçeler (Rationale)

- İzlenebilirlik: Tek JSON → tek generator çıktısı → UI var’ları; değişiklik kaynağı nettir.  
- Uyum: data-attr isimleri ve appearance modları Figma modlarıyla hizalanır.  
- Bakım: Palette/overlay değerleri koda gömülmez; Figma export değiştirmek yeterlidir.  
- Test edilebilirlik: Acceptance adımları data-theme/overlay/table tone farklarını ölçebilir.

# 5. Sonuçlar (Consequences)

### 5.1. Olumlu Sonuçlar
- Tema değişiklikleri tahmin edilebilir; UI “değişmedi” problemi azalır.  
- Yeni mod/aks eklemek yalnız token + generator güncellemesiyle yapılır.  
- Kodda sabit renk/px azaltılır, var(--…) kullanımı artar.

### 5.2. Olumsuz Sonuçlar
- Token formatı değişince generator ve runtime uyumlu güncellenmek zorunda.  
- Figma export yoksa yeni palette/mod eklenemez; bağımlılık artar.

# 6. Uygulama Detayları (Implementation Notes)

- figma.tokens.json: appearance modları (serban-light/dark/hc/compact/…), akslar, overlay min/max/default + opacity default, palette tanımları tutulur.  
- Generator (`scripts/theme/generate-theme-css.mjs`): mod listesini token’dan türetir, aks ve overlay default var’larını üretir; token’da olmayan mod için CSS yazmaz.  
- Runtime (`packages/ui-kit/src/runtime/theme-controller.ts`): root’a yalnız data-attr yazar; clamp değerleri token’dan gelir; overlay var’larını günceller.  
- Shell/provider: `data-theme`yi token modlarıyla hizalar; sabit renk/px mümkün olduğunca var(--…) kullanır.  
- Acceptance: data-theme değişiminde yüzey/kontrast, radius, table-surface-tone, overlay slider var değişimi doğrulanır.

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                      |
|-------------|----------|--------------------------|
| 2025-12-06  | Proposed | İlk taslak oluşturuldu   |

# 8. Notlar

- Token mod isimleri generator ve runtime’da birebir kullanılmalıdır.  
- Palette’nin genişlemesi halinde token → generator → runtime map’i birlikte güncellenir.
