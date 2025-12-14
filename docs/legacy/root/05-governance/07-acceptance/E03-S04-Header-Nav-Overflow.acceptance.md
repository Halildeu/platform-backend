---
title: "ACCEPTANCE – E03-S04 Header Navigasyon & Overflow"
story_id: E03-S04-Header-Nav-Overflow
status: draft
owner: "@halil"
last_review: 2025-12-06
modules:
  - frontend-shell
---

# 1. Amaç
`E03-S04-Header-Nav-Overflow` teslimatının tamamlanmış sayılabilmesi için gerekli kabul kriterlerini tanımlar. Header nav’ın overflow davranışı, sağ blok sabitliği ve kademeli gizleme kuralları doğrulanır.

# 2. Traceability (Bağlantılar)
- **Epic:** docs/05-governance/01-epics/E03_ThemeAndLayout.md
- **Story:** docs/05-governance/02-stories/E03-S04-Header-Nav-Overflow.md
- **Spec:** docs/05-governance/06-specs/SPEC-E03-S04-HEADER-NAV-BEHAVIOR.md
- **ADR:** docs/05-governance/05-adr/ADR-021-header-navigation-behavior.md
- **PROJECT_FLOW:** E03-S04 satırı

# 3. Kapsam (Scope)
1. Nav görünürlüğü ve overflow menüsü  
2. Sağ blok sabitliği (dil/görünüm/bildirim/kullanıcı)  
3. Aktif rota vurgusu (nav ve overflow)  
4. Kademeli gizleme (dil/login metinleri)  
5. Erişilebilirlik (focus/klavye/ESC/dış tık)

# 4. Acceptance Kriterleri

### Frontend Shell
- [ ] Geniş ekranda (≥1440px) tüm nav başlıkları görünür, “…” görünmez; aktif rota doğru başlıkta accent vurgusu alır.
- [ ] Dar ekranda (~1024px) sağ blok sabit kalır; nav soldan kırpılır, gizlenen başlıklar “…” altında listelenir; aktif rota overflow’da da vurgulanır.
- [ ] En dar senaryoda nav boş olsa dahi “…” butonu erişilebilir; sağ blok tek satırda kalır, nav altına kaymaz.
- [ ] Dil/görünüm/login: genişlik azaldıkça önce label, sonra metin kaybolur; ikonlar kalır (bayrak/ikon/avatardan vazgeçilmez).
- [ ] Erişilebilirlik: “…” focus/enter ile açılır, ESC veya dış tık ile kapanır; header içindeki tüm interaktifler focus ring gösterir.

### Rota Doğruluğu
- [ ] /admin/users → “Kullanıcılar” aktif  
- [ ] /access/roles → “Erişim” aktif  
- [ ] /audit/events → “Denetim” aktif  
- [ ] /suggestions → “Öneriler” aktif  

