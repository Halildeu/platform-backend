# Story E03-S04 – Header Navigasyon & Overflow

- Epic: E03 – Theme & Layout System  
- Story Priority: 320  
- Tarih: 2025-12-06  
- Durum: Ready  
- Modüller / Servisler: frontend-shell

## 1. Kısa Tanım
Header’ı tek panel halinde; nav’ı esnek, sağ bloğu sabit tutarak sığmayan başlıkları “…” menüsüne taşıyan, aktif rotayı her durumda vurgulayan yeni davranışı uygular.

## 2. İş Değeri
- Sağ blok (dil/görünüm/uyarı/kullanıcı) hiçbir zaman kaybolmaz veya alt satıra düşmez.  
- Sığmayan nav başlıkları deterministik biçimde “…” altına alınır; kullanıcı kaybolan menüleri bulabilir.  
- Aktif sayfa vurgusu her zaman görünür, karışıklık azalır.  
- Dar ekranda da erişilebilir, temiz header deneyimi sağlanır.

## 3. Bağlantılar (Traceability Links)
- SPEC: docs/05-governance/06-specs/SPEC-E03-S04-HEADER-NAV-BEHAVIOR.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md  
- ADR: ADR-021-header-navigation-behavior.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md

## 4. Kapsam
### In Scope
- Nav/overflow davranışı, aktif rota vurgusu.
- Sağ blok sabitliği ve kademeli gizleme kuralları.
- Header UI/UX (tek panel, border/shadow).

### Out of Scope
- Yeni menü item’i eklemek.
- Tema token değişikliği.

## 5. Task Flow (Ready → InProgress → Review → Done)
```text
+---------------+----------------------------------------------------+------------+-------------+---------+------+
| Modül/Servis  | Task                                               | Ready      | InProgress  | Review  | Done |
+---------------+----------------------------------------------------+------------+-------------+---------+------+
| frontend-shell| Nav görünürlüğü/overflow ölçüm ve render düzeni    | 2025-12-06 |             |         |      |
| frontend-shell| Sağ blok sabitliği, kademeli gizleme               | 2025-12-06 |             |         |      |
| frontend-shell| Aktif rota tespiti (en uzun prefix) ve stil       | 2025-12-06 |             |         |      |
+---------------+----------------------------------------------------+------------+-------------+---------+------+
```

## 6. Fonksiyonel Gereksinimler
1) Yeterli genişlikte tüm nav başlıkları görünür, “…” gizlenir.  
2) Dar alanda başlıklar soldan gizlenir, “…” görünür ve gizlenenleri listeler.  
3) Sağ blok sabit kalır; nav asla sağ bloğun altına düşmez.  
4) Aktif rota nav’da veya overflow’da accent vurgusu alır.  
5) “…” focus/enter ile açılır, ESC/dış tık ile kapanır.

## 7. Non-Functional Requirements
- UX/A11y: Focus ring görünür; dar ekranda da erişilebilir; ikon/metin kademeli gizleme ile okunabilirlik korunur.  
- Performance: Ölçüm/hesaplama client-side, header render’ında anlamlı gecikme yaratmaz.

## 8. İş Kuralları / Senaryolar
- “Dar ekran”: width < breakpoint → soldan gizle, “…” açılır listede gizlenenleri göster.  
- “Geniş ekran”: tüm item’lar görünür, “…” yok.  
- “Aktif rota”: en uzun prefix kuralı; /admin/users → Kullanıcılar, /access/roles → Erişim.

## 9. Interfaces (API / DB / Event)
- API/DB değişikliği yok. Sadece UI davranış katmanı.

## 10. Acceptance Criteria
Bkz. `docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md`.
