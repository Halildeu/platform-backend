# ADR-003 – Auth & BroadcastChannel Stratejisi

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E01-S05-IDENTITY-AUTH-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`
- STORY: E01-S10-Auth-BroadcastChannel-Full-MFE-Sync.md, E01-S05-Login.md
- İlgili ADR’ler: ADR-001, ADR-002, ADR-007
- STYLE GUIDE: (Varsa STYLE-BE-001 / STYLE-API-001)

---

# 1. Bağlam (Context)

- Mikro-frontendler farklı sekmelerde açıldığında oturum yönetimi senkron kalmak zorunda.
- Access token'ı localStorage'da tutmak XSS riskini artırıyor; HTTP-only cookie ile refresh güvenli.
- Remoteların token'a doğrudan erişmesi güvenli değil; shell üzerinden servis olarak verilmesi gerekiyor.

---

# 2. Karar (Decision)

- Access token yalnız bellek + kapalı kapsamlı cache'te tutulacak; refresh token HTTP-only cookie ile yönetilecek.
- Sekmeler arası token güncellemesini `BroadcastChannel` ile yapacağız; desteklemeyen tarayıcılara graceful fallback eklenecek.
- Remotelar token veya oturum verisine sadece `ShellServices.auth.getToken` ve `onTokenChange` aracılığıyla erişecek.
- Logout işlemi tüm sekmelere 1 saniye içinde yansıtılacak şekilde tasarlanacak.

---

# 3. Alternatifler (Alternatives)

Bu ADR’de alternatifler detaylı yazılmamış olsa da olası opsiyonlar:
- LocalStorage / SessionStorage’ta token saklamak
- Her remote için ayrı auth yönetimi yapmak

Bu seçenekler XSS ve setup maliyetleri nedeniyle tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Access token’ı yalnız bellek ve HTTP-only cookie ile yönetmek XSS riskini azaltır.
- BroadcastChannel ile sekmeler arası senkronizasyon, kullanıcı deneyimini iyileştirir.
- Shell üzerinden kontrollü token erişimi, remoteların güvenlik yüzeyini küçültür.

---

# 5. Sonuçlar (Consequences)

- BroadcastChannel desteklemeyen ortamlarda (ör. eski Safari) test ve fallback yönetimi gerekiyor.
- Token yenileme sürecinin shell içinde merkezi olması ek loglama / hata ayıklama gerektiriyor.
- Servis sözleşmesi değişirse remotelar derhal uyumsuz hale gelir; semver disiplinine ihtiyaç var.

### Acceptance / Metrics

- Token yenileme kaçırma oranı ≈ 0 olmalı (monitoring ile takip).
- Çok sekmeli logout senaryosunda 1 saniye içinde tüm sekmelerde çıkış gerçekleşmeli.
- Sızdırılan token için güvenlik testleri (pen test / red team) yapılmalı.

---

# 6. Uygulama Detayları (Implementation Notes)

- `ShellServices.auth` sözleşmesi versionlanmalı ve değişiklikler ADR veya SPEC ile kayıt altına alınmalıdır.
- BroadcastChannel fallback mekanizması (örn. localStorage event, polling) netleştirilmeli.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-003 kararı kabul edildi |

---

# 8. Notlar

- Gelecekte Web Crypto / Token Binding gibi gelişmiş güvenlik mekanizmaları devreye alınırsa bu ADR gözden geçirilebilir.
