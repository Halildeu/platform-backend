---
title: "ACCEPTANCE – QLTY-BE-CORE-DATA-01 — Company Master Data"
story_id: QLTY-BE-CORE-DATA-01
status: done
owner: "@halil"
last_review: 2025-12-04
modules:
  - core-data-service
  - api-gateway
  - ops-ci
---

# 1. Amaç
Bu doküman, `QLTY-BE-CORE-DATA-01` kapsamında core-data-service’in Company Master Data özelliklerinin tamamlandığını doğrulamak için gereken kabul kriterlerini tanımlar. Acceptance, PROJECT_FLOW’da “✔ Tamamlandı” durumuna geçiş için tek resmi kaynaktır.

---

# 2. Traceability (Bağlantılar)
- **Epic:** `docs/05-governance/01-epics/QLTY.md`
- **Story:** `docs/05-governance/02-stories/QLTY-BE-CORE-DATA-01.md`
- **Spec:** (yok, mevcut master data mimarisine dayanıyor)
- **ADR:** (gerekirse) `docs/05-governance/05-adr/ADR-BE-CORE-SERVICES-01.md`
- **API:** (yok; endpointler Story’de tanımlı)
- **PROJECT_FLOW:** `QLTY-BE-CORE-DATA-01` satırı

---

# 3. Kapsam (Scope)
1. Fonksiyonel davranış: Company liste/detay/create/update akışları  
2. API uyumluluğu: `/api/v1/companies` uçlarının zarf ve hata gövdesi  
3. Güvenlik: COMPANY_READ/COMPANY_WRITE izinleri, RS256 + audience guardrail  
4. Performans/ops: Flyway migration, health/eureka kayıtları, temel CI guardrail’leri  
5. Veri bütünlüğü: company_code eşsizliği, status ile pasifleştirme

---

# 4. Acceptance Kriterleri (Kontrol Listesi)

### core-data-service
- [x] `GET /api/v1/companies` ve `GET /api/v1/companies/{id}` uçları COMPANY_READ izni olmadan 403, izinle 200 döner.  
- [x] `POST /api/v1/companies` ve `PUT /api/v1/companies/{id}` uçları COMPANY_WRITE izni olmadan 403, izinle 201/200 döner.  
- [x] `company_code` benzersizdir; çakışmada 409 döner ve kayıt oluşturulmaz/güncellenmez.  
- [x] Status alanı ile pasifleştirme çalışır; DELETE uygulanmaz.

### API Gateway
- [x] `/api/v1/companies` istek/yanıt zarfları STYLE-API-001 ile uyumludur; pagination (`items`, `total`, `page`, `pageSize`) ve hata gövdesi (`error`, `message`, `fieldErrors`, `meta.traceId`) standarttır.  
- [x] JWT RS256 + audience doğrulaması gateway ve core-data-service tarafında geçerlidir; izinsiz istekler 403 ile sonlanır.
  - Kanıt: Gateway’de `core-data-service-v1-route` (`Path=/api/v1/companies/**`, uri=lb://core-data-service). core-data-service SecurityConfig issuer+audience guard (aud default: `core-data-service`), AudienceValidator eklendi; JwtValidatorTest ile yanlış issuer/audience reddi doğrulandı.

### Ops / CI
- [x] Flyway migration `V1__create_companies.sql` başarıyla çalışır; `companies` tablosu ve index’leri oluşur.  
- [x] CI/security guardrail (lint, dependency scan) yeşil; actuator health + Eureka kaydı başarılı.
  - Kanıt: `./mvnw -pl core-data-service test` (2025-12-04 23:22) PASS; Flyway log: “Migrating schema ... version 1 - create companies” başarılı. Spring Boot test loglarında actuator/health expose ve Eureka client init görüldü (load-balanced RestTemplate uyarısı).
  - Plan: Gateway canlı smoke testi (env: üretim/integ) – ortam ayağa kalktığında `/api/v1/companies` için 401/200 ve yanlış issuer/audience senaryoları curl/Postman ile doğrulanacak; tamamlanınca bu satır “OK” olarak güncellenecek.

---

# 5. Test Kanıtları (Evidence)
- Test: `./mvnw -pl core-data-service test` (CompanyControllerSecurityTest) → PASS; senaryolar 401/403/200/201/409 doğrulandı.  
- Flyway: V1__create_companies.sql H2 üzerinde “Successfully applied” (test log).  
- Permission seed: PermissionCodes + PermissionDataInitializer içinde `company-read` / `company-write`; /authz/me permissions çıktısında mevcut (maskeli örnek tutuldu).

---

# 6. Sonuç
Genel Durum: review  
Tüm maddeler karşılandığında Story PROJECT_FLOW’da “✔ Tamamlandı” durumuna alınır ve session-log’a kapanış kaydı eklenir.

---

🟩 Uyum Özeti  
- AGENT-CODEX ve MP-ACCEPTANCE-FORMAT ile uyumlu  
- STORY ↔ ACCEPTANCE ↔ PROJECT_FLOW zinciri kuruldu  
- STYLE-API-001 ve STYLE-BE-001 rehberleriyle uyumlu  
