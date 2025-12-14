# Story QLTY-BE-CORE-DATA-01 — Company Master Data (core-data-service)

- Epic: QLTY – Güvenlik / Yetkilendirme İyileştirmeleri  
- Story Priority: 980  
- Tarih: 2025-12-03  
- Durum: ✔ Tamamlandı  
- Modüller / Servisler: core-data-service

## 1. Kısa Tanım
Company master data için core-data-service kurulumu: şirket kartı CRUD (liste/detay/create/update), permission guard (COMPANY_READ/COMPANY_WRITE), data-scope yok (global master).

## 2. İş Değeri
- Tüm domain servislerinin referans alacağı tekil company kaynağı sağlar.  
- Yetkisiz erişimi permission ile sınırlar; scope karmaşıklığını master data’dan uzak tutar.  
- İleride Branch/Warehouse/CostCenter gibi master dataları eklemek için temel oluşturur.

## 3. Bağlantılar (Traceability Links)
- SPEC: (yok, mevcut master data mimarisine dayanıyor)  
- ACCEPTANCE: `docs/05-governance/07-acceptance/AT-BE-CORE-DATA-01.md`  
- ADR: `docs/05-governance/05-adr/ADR-BE-CORE-SERVICES-01.md` (varsa)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## 4. Kapsam

### In Scope
- core-data-service kurulumu (Spring Boot modülü, migration, security).  
- Company CRUD: `GET /api/v1/companies`, `GET /api/v1/companies/{id}`, `POST`, `PUT`.  
- Permission guard: COMPANY_READ / COMPANY_WRITE.  
- Migration: `companies` tablosu (company_code uniq, name, type, tax info, country/city, status, audit alanları).

### Out of Scope
- Data-scope (COMPANY/PROJECT) uygulanmayacak (master data global).  
- Soft-delete yerine status ile pasifleştirme.  
- Branch/Warehouse/CostCenter vs. sonraki faz.

## 5. Task Flow (Ready → InProgress → Review → Done)

```text
+--------------------+----------------------------------------------------------+------------+-------------+---------+------------+
| Modül/Servis       | Task                                                     | Ready      | InProgress  | Review  | Done       |
+--------------------+----------------------------------------------------------+------------+-------------+---------+------------+
| core-data-service  | Servis iskeleti (pom, app, security)                     | 2025-12-03 | 2025-12-03  | 2025-12-04 | 2025-12-04 |
| core-data-service  | Migration: companies tablosu                             | 2025-12-03 | 2025-12-03  | 2025-12-04 | 2025-12-04 |
| core-data-service  | Company CRUD (controller/service/repo) + perm guard      | 2025-12-03 | 2025-12-03  | 2025-12-04 | 2025-12-04 |
| governance         | Acceptance dosyası, PROJECT_FLOW güncellemesi            | 2025-12-03 | 2025-12-04  | 2025-12-04 | 2025-12-04 |
+--------------------+----------------------------------------------------------+------------+-------------+---------+------------+
```

## 6. Fonksiyonel Gereksinimler
1. Şirket listesi ve detay uçları COMPANY_READ izni ile erişilir.  
2. Create/Update uçları COMPANY_WRITE izni ile korunur; company_code uniq’tir.  
3. Status alanı ile pasifleştirme yapılır; DELETE yok.  
4. Migration şirket alanlarını (code/name/type/tax/country/city/status/audit) içerir.

## 7. Non-Functional Requirements
- Security: JWT RS256 + audience guardrail; yalnız permission check (scope yok).  
- Performance: Basit pagination + filtre; code/status index’i.  
- Ops: Eureka kaydı, actuator health; Flyway migration etkin.

## 8. İş Kuralları / Senaryolar
- “Admin şirket kartlarını listeler/detayını görür” → COMPANY_READ yeterlidir.  
- “Yetkili kullanıcı şirket kartı oluşturur/günceller” → COMPANY_WRITE zorunlu; code uniq olmazsa 409.  
- “Pasif şirket” → status alanı ile temsil edilir; DELETE yapılmaz.

## 9. Interfaces (API / DB / Event)

### API
- `GET /api/v1/companies` – paged, code/name/status filtreleri.  
- `GET /api/v1/companies/{id}` – detay.  
- `POST /api/v1/companies` – create.  
- `PUT /api/v1/companies/{id}` – update.

### Database
- `companies` tablosu (id, company_code uniq, company_name, short_name, company_type, tax_number, tax_office, country_code, city, status, created/updated alanları).  
- Index: company_code, status.

### Events
- Bu fazda event yok; audit/log mevcut sistemde.

## 10. Acceptance Criteria
- [x] LIST/DETAIL uçları COMPANY_READ izni olmadan erişilemez, izinle 200 döner.  
- [x] Create/Update COMPANY_WRITE izni olmadan 403, izinle 201/200 döner.  
- [x] company_code uniq değilse 409 döner.  
- [x] Migration şirket tablosunu oluşturur; Flyway log başarılıdır.

## 11. Definition of Done
- [x] Acceptance maddeleri sağlandı.  
- [x] Kod review onayı alındı.  
- [x] PROJECT_FLOW, session-log/runbook güncellendi.  
- [x] Testler (service/controller) yeşil. 

## 12. Notlar
- core-data-service master data içindir; data-scope uygulanmaz.  
- İleride Branch/Warehouse/CostCenter gibi master datalar aynı servise eklenecek.  
- Status alanı soft-delete yerine kullanılır.
