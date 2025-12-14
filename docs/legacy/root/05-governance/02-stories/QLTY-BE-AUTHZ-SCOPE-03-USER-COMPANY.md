# Story QLTY-BE-AUTHZ-SCOPE-03 — User-Service Company Scope Filtrelemesi

- Epic: QLTY – Güvenlik / Yetkilendirme İyileştirmeleri  
- Öncelik: 997  
- Tarih: 2025-12-03  
- Durum: Devam ediyor  
- Modüller: user-service, permission-service (authz), api-gateway (JWT doğrulama)

## 1) Kısa Tanım
User-service, permission-service’ten gelen `AuthorizationContext` ile şirket (COMPANY) scope’una göre veri sınırlandırır. admin/superAdmin kullanıcılar data-scope kontrolünden muaftır.

Not: SCOPE-03 kapsamı, gerçekten company/project bazlı veri tutan servislerle sınırlıdır. Variant-service global katalog olduğu için data-scope uygulanmayacak; /authz/me yalnız permissions için kullanılır.

## 2) Yapılanlar
- Migration: `V7__add_company_id_to_users.sql` ile `users.company_id` (nullable) ve index eklendi.
- Entity: `User.companyId` alanı eklendi.
- Service: `UserService.searchUsers(...)`  
  - superAdmin → tam liste  
  - normal user → company_id NULL (global) kullanıcılar her zaman görünür; `allowedCompanyIds` boş ise sadece NULL kayıtlar, dolu ise `company_id IN allowedCompanyIds OR company_id IS NULL`.
- AuthZ tüketimi: `/authz/me` kontratı (permissions, allowedScopes[{scopeType, scopeRefId}], superAdmin) ile uyumlu.
- Testler: `UserServiceTest` senaryoları admin vs scoped user vs boş scope ile çalışır durumda.

## 3) Kalanlar / Sonraki Adımlar
- `users.company_id` için backfill ve gerekiyorsa NOT NULL sıkılaştırması (ayrı migration).  
- FE tarafında company seçimi gösterimi (ops/UI).  
- PROJECT_FLOW notu güncel tutulacak; SCOPE-03 ilerledikçe acceptance’a yansıtılacak.

## 4) Acceptance Kriterleri
- [x] `users.company_id` kolonu ve index eklendi.  
- [x] `searchUsers` COMPANY scope filtresi aktif; superAdmin bypass.  
- [x] Testler: admin tüm kayıtları, scoped user yalnız kendi company’leri, scope boş ise boş sonuç.  
- [ ] Backfill/NOT NULL kararı uygulandı (ops kararı sonrası).  
- [ ] FE tarafında company alanı/filtreleri güncellendi (ayrı iş).

## 5) Notlar
- Data-scope kontratı: `/authz/me` → `permissions`, `allowedScopes[{scopeType, scopeRefId}]`, `superAdmin`.  
- company_id NULL (global) kullanıcılar tüm yetkili kullanıcılara görünür; COMPANY scope yalnız company’li kayıtları sınırlar. 403 yerine boş liste stratejisi geçerli.  
- SCOPE-04’de variant-service için company/project kolonları eklenip aynı filtre modeli açılacak.
