# Story QLTY-BE-AUTHZ-SCOPE-03-PROJECT — Proje/Şantiye Data-Scope

- Epic: QLTY – Güvenlik / Yetkilendirme İyileştirmeleri  
- Story Priority: 996  
- Tarih: 2025-12-03  
- Durum: Planlandı  
- Modüller / Servisler: project-service

## 1. Kısa Tanım
Proje/şantiye listeleme ve arama uçlarında PROJECT scope ile veri sınırlandırılır; superAdmin data-scope’dan muaftır.

## 2. İş Değeri
- Şirket/proje verisinin yetkisiz erişimini engeller.  
- Proje listeleri, kullanıcının yetkili olduğu projelerle sınırlanır.  
- Scope modeli permission-service kontratıyla uyumlu hâle gelir.

## 3. Bağlantılar (Traceability Links)
- SPEC: (yok, mevcut scope spesifikasyonuna dayanıyor)  
- ACCEPTANCE: `docs/05-governance/07-acceptance/AT-BE-AUTHZ-SCOPE-PROJECT.md` (oluşturulacak)  
- ADR: `docs/05-governance/05-adr/ADR-AUTHZ-SCOPE-01.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## 4. Kapsam

### In Scope
- project-service proje/şantiye listeleme/arama uçları (`/api/v1/projects`, `/api/v1/projects/search` vb.).  
- /authz/me tüketimi ile PROJECT scope filtresi.  
- Service/repo katmanında `projectId IN allowedProjectIds` kuralı.  
- superAdmin bypass.

### Out of Scope
- Variant/preset alanı (global, scope uygulanmaz).  
- ROLE visibility; yalnız dokümanda notlu.  
- Proje dışındaki domain’ler (finans/depo/HR) bu Story’nin dışında.

## 5. Task Flow (Ready → InProgress → Review → Done)

```text
+------------------+--------------------------------------------------------------+------------+-------------+---------+------+
| Modül/Servis     | Task                                                         | Ready      | InProgress  | Review  | Done |
+------------------+--------------------------------------------------------------+------------+-------------+---------+------+
| project-service  | PROJECT scope filtresi (/authz/me → allowedProjectIds)       | 2025-12-03 |             |         |      |
| project-service  | Repo metodu: searchByIdIn + service testleri (admin/scope)   | 2025-12-03 |             |         |      |
| governance       | Acceptance dosyası ve PROJECT_FLOW güncellemesi              | 2025-12-03 |             |         |      |
+------------------+--------------------------------------------------------------+------------+-------------+---------+------+
```

## 6. Fonksiyonel Gereksinimler
1. /authz/me yanıtındaki scopeType=PROJECT değerleri `allowedProjectIds` olarak alınır.  
2. superAdmin → tüm projeler listelenir.  
3. Normal kullanıcı → sadece `projectId IN allowedProjectIds`; scope boş ise boş sonuç (200 + empty).  
4. Liste/arama uçları mevcut filter/search davranışıyla birlikte çalışır.

## 7. Non-Functional Requirements
- Security: RS256 + audience guardrail değişmez; scope filtresi yalnızca veri görünürlüğünü sınırlar.  
- Performance: IN filtresi için index kullanımı; repository sorguları mevcut performans bütçesini aşmamalı.  
- Audit/Trace: TraceId ve audit log’larda scope kararları izlenebilir olmalı.

## 8. İş Kuralları / Senaryolar
- “Admin tüm projeleri görür” → superAdmin bayrağı true ise data-scope uygulanmaz.  
- “Scoped kullanıcı yalnız kendi projelerini görür” → allowedProjectIds kesişimi uygulanır.  
- “Scope yok” → sonuç boş (403 değil, 200 + empty).  
- “Proje kaydı her zaman bir ID’ye sahiptir; NULL/global proje yok.”

## 9. Interfaces (API / DB / Event)

### API
- `GET /api/v1/projects` – sayfalanmış liste; PROJECT scope filtresi uygulanır.  
- `GET /api/v1/projects/search` – arama/liste; PROJECT scope filtresi uygulanır.

### Database
- projects (varsayım: `id` PK, `company_id` mevcut)  
- Sorgu: `WHERE project_id IN (:allowedProjectIds)`; scope boş ise sonuç boş.

### Events
- Bu Story’de yeni event yok; mevcut audit/log yapısı kullanılacak.

## 10. Acceptance Criteria
- [ ] superAdmin ile çağrıldığında tüm projeler listelenir.  
- [ ] PROJECT scope’u [P1, P2] olan kullanıcı sadece P1/P2 projelerini görür.  
- [ ] PROJECT scope’u boş olan kullanıcı boş liste alır (200 + empty).  
- [ ] project-service /authz/me kontratını doğru tüketir (permissions + allowedScopes + superAdmin).

## 11. Definition of Done
- [ ] Acceptance maddeleri sağlandı.  
- [ ] Kod review onayı alındı.  
- [ ] PROJECT_FLOW, session-log/runbook güncellendi.  
- [ ] Yeni/var olan testler (admin vs scoped vs boş scope) yeşil.

## 12. Notlar
- Variant/preset alanı globaldir; data-scope uygulanmaz, yalnız permissions kullanılır.  
- ROLE visibility yalnız dokümanda notlu; ihtiyaç olursa ayrı iş olarak ele alınacak.  
- Boş scope için strateji: 200 + empty; 403 uygulanmaz.
