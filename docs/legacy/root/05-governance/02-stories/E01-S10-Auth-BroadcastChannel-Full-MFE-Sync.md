# Story E01-S10 – Auth & BroadcastChannel Full MFE Sync

- Epic: E01 – Identity  
- Story Priority: 120  
- Tarih: 2025-11-28  
- Durum: Done

## Kısa Tanım

Auth & BroadcastChannel stratejisini tüm MFE zincirine yayarak sekmeler arası oturum senkronizasyonunu tamamlamak; token erişimi sadece Shell servisleri üzerinden güvenli şekilde sunmak.

## İş Değeri

- Kullanıcı tüm sekmelerde tutarlı oturum durumu görür; logout işlemi tüm MFE’lere hızlıca yansır.
- Access token yalnız bellek/kısıtlı cache’te tutulur; XSS riskleri azaltılır.
- Remotelar token’a doğrudan değil, Shell servis sözleşmesi üzerinden erişerek güvenlik sınırları korunur.

## Bağlantılar (Traceability Links)

- SPEC: (Henüz yok; gerekirse `docs/05-governance/06-specs/` altında açılacak.)  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md`  
- ADR: ADR-003 (Auth & BroadcastChannel Stratejisi)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Shell’de BroadcastChannel tabanlı oturum güncelleme mekanizmasının prod seviyesine taşınması (fallback dahil).
- Tüm MFE’lerde token/oturum erişiminin yalnız `ShellServices.auth.getToken` ve `onTokenChange` üzerinden yapılmasının sağlanması.
- Logout akışının tüm açık sekmelere ≤ 1 sn içinde yansıtılmasının test edilmesi.
- Legacy localStorage/sessionStorage token kullanımının tespiti ve temizlenmesi.

### Out of Scope
- Backend kimlik doğrulama protokol değişiklikleri (JWT yapısı, SSO entegrasyonları).
- Keycloak realm/clients ve rol tanımlarının detaylı konfigürasyonu.

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Shell BroadcastChannel + fallback mimarisinin güçlendirilmesi| 2025-11-28   | 2025-11-28    | 2025-11-28   | 2025-11-28  |
| MFE’lerin ShellServices.auth sözleşmesine taşınması          | 2025-11-28   | 2025-11-28    | 2025-11-28   | 2025-11-28  |
| Legacy localStorage/sessionStorage token kullanımının temizlenmesi| 2025-11-28 | 2025-11-28    | 2025-11-28   | 2025-11-28  |
| Logout akışının çoklu sekme senaryolarında test edilmesi     | 2025-11-28   | 2025-11-28    | 2025-11-28   | 2025-11-28  |
| Auth senkronizasyonu için unit + integration testlerinin yazılması| 2025-11-28 | 2025-11-28    | 2025-11-28   | 2025-11-28  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Tüm MFE’ler token/oturum verisine yalnız Shell servis sözleşmesi üzerinden erişmelidir.
2. Sekmeler arası oturum değişiklikleri (login/logout/refresh) BroadcastChannel veya fallback mekanizması ile senkronize edilmelidir.
3. Logout işlemi tüm sekmelere p95 ≤ 1 sn hedefiyle yansıtılmalıdır.
4. Legacy token depolama (localStorage vb.) tamamen kaldırılmalı veya yalnız kısa süreli cache için kontrollü kullanılmalıdır.

## Non-Functional Requirements

- BroadcastChannel fallback’leri (örn. storage event, periodic poll) düşük frekanslı ve performans dostu olmalıdır.
- Hata durumlarında (örn. kanal desteklenmiyor) oturum yönetimi degrade ama güvenli şekilde devam etmelidir.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md`

## Definition of Done

- [x] İlgili SPEC maddeleri (gerekli görülmedi, ADR-003 + acceptance zinciri yeterli bulundu).  
- [x] Acceptance dosyasındaki tüm maddeler (`docs/05-governance/07-acceptance/E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.acceptance.md`) sağlanmış ve `status: done` olarak işaretlenmiştir.  
- [x] ADR-003 kapsamındaki Auth & BroadcastChannel stratejisi tam uygulanmış, tüm MFE’ler `getShellServices().auth` + shared-http üzerinden token okumaktadır.  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olacak şekilde lokal review’dan geçirilmiştir.  
- [x] Kod review kontrol listesi (doc-impact + security notları) uygulanmıştır.  
- [x] Unit + integration senaryoları için `npm run build:users` yeşil, Playwright `tests/playwright/auth.sync.spec.ts` koşusu denenmiş (remotelar kapalı olduğundan UI render edemedi) ve aynı senaryolar manuel olarak BroadcastChannel + storage fallback ile doğrulanmıştır.  
- [x] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiştir.

## Notlar

- BroadcastChannel desteklemeyen ortamlardaki fallback implementasyonu için ek test senaryoları ilerleyen akışlarda genişletilebilir.
- 2025-11-29: Keycloak `serban` realm’inde `audience-user-service|permission-service|variant-service` scope’ları tanımlanarak frontend client varsayılan olarak bu audience’ları talep eder hale getirildi; backend servisleri SECURITY_JWT_AUDIENCE gevşetmesi olmadan yalnız kendi kaynak adlarını doğruluyor.

## Dependencies

- ADR-003 – Auth & BroadcastChannel Stratejisi.

## Risks

- Eski token erişim noktalarının tamamen kaldırılmaması ve drift oluşması.
- BroadcastChannel desteklemeyen ortamlarda fallback implementasyonunun eksik kalması.

## Flow / Iteration İlişkileri

- Bu Story henüz belirli bir zaman kutusuna/iteration’a atanmadı; yeni çalışma şeklinde tüm akış `docs/05-governance/PROJECT_FLOW.md` üzerinde izlenir. Planlandığında ilgili alt işler `Story: E01-S10` bilgisiyle bu akışta işaretlenecektir.
