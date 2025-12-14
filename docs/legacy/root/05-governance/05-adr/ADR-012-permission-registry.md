# ADR-012 – Permission Registry

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- SPEC: `docs/05-governance/06-specs/SPEC-E08-S01-PERMISSION-REGISTRY-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`
- STORY: E08-S01-Permission-Registry.md
- İlgili ADR’ler: ADR-002
- STYLE GUIDE: (Varsa STYLE-BE-001 / STYLE-API-001)

---

# 1. Bağlam (Context)

- Access modülü ve rota korumaları için permission anahtarları kullanıyoruz; farklı MFE’lerde adlandırma ve yaşam döngüsü uyumsuzlukları oluştu.
- Deprecate edilen izinler takipsiz kalıyor; UI’de hangi izinlerin devrede olduğu tam bilinmiyor.

---

# 2. Karar (Decision)

- Tek bir permission registry (JSON/YAML + Backstage plugin) oluşturulacak; anahtar, açıklama, sahip, durum (active/deprecated) alanları içerecek.
- Manifest ve PageLayout, permission anahtarlarını bu registry üzerinden doğrulayacak (CI contract testleri). 
- Deprecate edilen izinler için sunset planı ve tarih tutulacak; UI’de gerekirse tooltip ile “yakında kaldırılacak” mesajı.
- Registry değişiklikleri versiyonlanacak; release notlarında yer alacak.

---

# 3. Alternatifler (Alternatives)

- Her MFE’nin kendi permission listesini tutması
- Permission anahtarları için merkezi doküman yerine sözlü/dağınık bilgiye güvenmek

Bu alternatifler izlenebilirlik ve bakım zorluğu nedeniyle tercih edilmemiştir.

---

# 4. Gerekçeler (Rationale)

- Merkezi permission registry, hangi iznin nerede ve nasıl kullanıldığını netleştirir.
- Sunset planları, deprecated izinlerin kontrollü şekilde kaldırılmasını sağlar.

---

# 5. Sonuçlar (Consequences)

- Permission anahtarları için ek bakım gerekecek; yeni anahtar eklemek review sürecine tabi olacak.
- Registry olmadan erişim sağlayan legacy servislerin migrasyonu planlanmalı.
- Backstage’de permission sayfası oluşturulmalı; kim hangi izinleri kullanıyor izlenebilecek.

### Acceptance / Metrics

- Registry dışı permission anahtarı kullanılmıyor (CI contract testi).
- Deprecate anahtarlar takvimli olarak temizleniyor; stale permission sayısı < hedef.
- Access/Audit ekranları registry meta verisini kullanarak tutarlı mesaj gösteriyor.

---

# 6. Uygulama Detayları (Implementation Notes)

- Registry şeması ve depolama formatı (JSON/YAML) standartlaştırılmalıdır.
- CI tarafında registry doğrulama adımları devreye alınmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-012 kararı kabul edildi |

---

# 8. Notlar

- İleride RBAC/ABAC kararları için yeni ADR’ler bu registry ile ilişkilendirilebilir.
