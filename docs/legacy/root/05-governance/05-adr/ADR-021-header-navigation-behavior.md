# ADR-021 – Shell Header Navigasyon Davranışı

**Durum (Status):** Proposed  
**Tarih:** 2025-12-06  

**İlgili Dokümanlar (Traceability):**
- EPIC: docs/05-governance/01-epics/E03_ThemeAndLayout.md
- SPEC: docs/05-governance/06-specs/SPEC-E03-S04-HEADER-NAV-BEHAVIOR.md
- STORY: docs/05-governance/02-stories/E03-S04-Header-Nav-Overflow.md
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S04-Header-Nav-Overflow.acceptance.md
- STYLE GUIDE: docs/00-handbook/NAMING.md

---

## 1. Bağlam
- Shell header’ında nav başlıkları (Ana Sayfa, Öneriler, Etik, Erişim, Denetim, Kullanıcılar) sağ blok (dil/görünüm/bildirim/kullanıcı) ile çakışıyor; sığmayan başlıklar rastgele kayboluyor.
- “…” menüsü bazen görünür kalıyor, bazen kayıyor; erişilebilirlik ve sezgisellik zayıf.
- AGENT-CODEX izlenebilirliği gerektiriyor; davranış değişikliği için ADR + SPEC + Story + Acceptance şart.

## 2. Karar
- Header tek panel olacak; sol taraf esnek (launcher + nav + overflow), sağ taraf sabit (dil/görünüm/bildirim/kullanıcı).
- Nav item’lar yeterli genişlik varken tamamen görünür; sığmadığında soldan sağa doğru gizlenip “…” menüsüne taşınır. “…” yalnızca overflow varsa görünür ve açılır menüde aynı stil/hover/active davranışı uygulanır.
- Aktif rota hem nav’da hem overflow’da accent vurgusuyla işaretlenir.
- Sağ blok hiçbir zaman nav’ın altına kaymaz; nav kırpılır, sağ blok sabit kalır.
- Dil/görünüm/login alanları daralırken kademeli olarak metinlerini gizler, ikonları korur.

## 3. Alternatifler
- Mevcut serbest büyüyen nav: Basit ama sağ blokla çakışıyor, aktiflik ve overflow bozuluyor.
- Breakpoint’lere göre sabit sayıda item: Tahmini davranış sağlıyor ama dinamik genişlikte hassasiyet yok.
- Seçilen: Dinamik ölçüm + overflow menüsü + sabit sağ blok.

## 4. Gerekçeler
- Erişilebilirlik: Aktif sekme ve “…” menüsü her zaman ulaşılabilir.
- Tutarlılık: Nav ve overflow aynı görsel/stil sözleşmesine bağlı.
- Esneklik: Ekran genişliği değiştiğinde deterministik gizleme sırası.
- İzlenebilirlik: Davranış SPEC/Story/Acceptance ile tanımlı.

## 5. Sonuçlar
### Olumlu
- Sağ blok taşmıyor; başlıklar deterministik biçimde “…” altına gidiyor.
- Aktif sayfa her durumda vurgulu, kullanıcı nerede olduğunu anlıyor.
- “…” menüsü erişilebilir ve nav ile aynı UX’e sahip.
### Olumsuz
- Dinamik ölçüm ve state yönetimi ek karmaşıklık getirir.
- Küçük ekranlarda metinlerin kademeli gizlenmesi için ekstra koşullar gerekir.

## 6. Uygulama Notları
- Nav için min-w-0 + overflow-hidden; ellipsis flex-shrink-0, yalnızca overflowItems > 0 iken görünür.
- Sağ blok shrink-0; dil/görünüm/login metinleri genişlik azaldıkça sırayla gizlenir, ikon kalır.
- Rota tespiti: En uzun prefix eşleşmesiyle activeKey; “/admin/users” → “Kullanıcılar”.

## 7. Durum Geçmişi
| Tarih       | Durum    | Not                     |
|-------------|----------|-------------------------|
| 2025-12-06  | Proposed | İlk taslak eklendi.     |
