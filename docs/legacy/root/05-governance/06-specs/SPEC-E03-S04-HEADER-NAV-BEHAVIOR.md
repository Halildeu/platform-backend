# SPEC-E03-S04 – Header Navigasyon Davranışı
**Başlık:** Shell Header Nav & Overflow  
**Versiyon:** v1.0-draft  
**Tarih:** 2025-12-06  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E03_ThemeAndLayout.md`  
- ADR: `docs/05-governance/05-adr/ADR-021-header-navigation-behavior.md`  
- STORY: `docs/05-governance/02-stories/E03-S04-Header-Nav-Overflow.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)
Header’ı tek panel olarak tanımlamak; nav’ı esnek, sağ bloğu (dil/görünüm/bildirim/kullanıcı) sabit tutarak sığmayan başlıkları deterministik biçimde “…” menüsüne taşıyan davranışı tarif etmek. Aktif rota her durumda vurgulanmalı, dar ekranda da erişilebilirlik korunmalıdır.

# 2. Kapsam (Scope)
### Kapsam içi
- Nav/overflow davranışı ve aktif rota vurgusu.
- Sağ blok sabitliği ve kademeli gizleme (dil/görünüm/login metinleri).
- Header UI/UX (tek panel, border/shadow, z-index).

### Kapsam dışı
- Yeni nav item’ı ekleme.
- Tema token değişiklikleri.

# 3. Tanımlar
- **Overflow menüsü:** Sığmayan nav öğelerinin listelendiği “…” açılır menüsü; nav ile aynı stil/hover/active kurallarını kullanır.
- **Kademeli gizleme:** Sağ blokta metinlerin dar alanda sırasıyla gizlenip ikonların kalması.

# 4. Fonksiyonel Gereksinimler
1. Rota eşleşmesi en uzun prefix kuralıyla yapılır; `/admin/users` → “Kullanıcılar”, `/access/roles` → “Erişim”.  
2. Geniş ekranda tüm nav item’ları görünür, “…” gizlidir.  
3. Dar alanda nav soldan gizlenir; gizlenenler “…” altında listelenir.  
4. Aktif rota nav’da veya overflow’da accent arka plan + border + shadow ile vurgulanır.  
5. “…” yalnızca overflowItems > 0 iken görünür; focus/enter ile açılır, ESC/dış tık ile kapanır.

# 5. Non-Functional Requirements
- UX / A11y: Focus ring görünür; “…” klavye ile açılıp ESC/dış tık ile kapanır; ikonlar her zaman kalır, metinler kademeli gizlenir.  
- Performans: Ölçüm ve slot hesaplaması header render’ında gözle görülür gecikme yaratmayacak kadar hafif olmalı.  
- i18n: Tüm metinler sözlükten çözülür, pseudo-locale’de kırılma olmaz.  
- Güvenlik: Ek API/DB değişikliği yok; sadece UI davranış katmanı.  

# 6. İş Kuralları / Senaryolar
- “Geniş ekran” → tüm başlıklar görünür, “…” gizli.  
- “Dar ekran” → soldan gizle, “…” ile listele, sağ blok sabit.  
- “En dar” → nav boş olabilir, “…” yine de görünür; sağ blok tek satırda.  
- “Aktif rota” → en uzun prefix; nav veya overflow’da accent vurgusu.  

# 7. Interfaces (API / DB / Event)
- API: Yok (mevcut endpointler değişmiyor).  
- Database: Yok.  
- Events: Yok.  

# 5. Teknik Kurallar
- Layout: Header tek panel; sol blok `flex:1, min-w:0`, sağ blok `shrink:0`.  
- Nav container: `min-w-0`, `overflow-hidden`; ellipsis `flex-shrink:0`, nav dışında hizalanır.  
- Ölçüm stratejisi: Sağ blok için rezerv alan bırakılır; kalan genişlikten slot sayısı hesaplanır, visible/overflow listeleri buna göre üretilir.  
- Sağ blok kademeli gizleme: Dil/görünüm/login metinleri genişlik azaldıkça sırasıyla gizlenir; ikonlar kalır.

# 8. Kabul (Reference)
- Ayrıntılı kabul maddeleri için: `docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md`

# 9. İzlenebilirlik
- ADR-021 tek mimari karar kaynağıdır; kod değişiklikleri ADR/SPEC/Story/Acceptance zincirine referans vermelidir.
