# ADR-001 – Remote Manifest & SRI

**Durum (Status):** Accepted  
**Tarih:** 2025-11-02  

**İlgili Dokümanlar (Traceability):**
- EPIC: `docs/05-governance/01-epics/E04_PlatformManifestAndContracts.md`
- SPEC: `docs/05-governance/06-specs/SPEC-E04-S01-PLATFORM-MANIFEST-V1.md`
- ACCEPTANCE: `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`
- STORY: `docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md`
- İlgili ADR’ler: ADR-002 (Manifest Model), ADR-006 (Canary & Rollback), planlanan ADR-007, ADR-013
- STYLE GUIDE: (Varsa STYLE-FE-001 / STYLE-API-001)

---

# 1. Bağlam (Context)

- Shell, mikro-frontend remotelarını runtime’da gateway üzerinden manifest ile keşfediyor. Manifest olmadan sürüm uyumsuzluğu ancak kullanıcı hatasıyla görünür hâle geliyor.
- Üçüncü parti script’lerin doğrulanmaması tedarik zinciri ve XSS riskini artırıyor.
- CSP/SRI uygulanmadığında remote kaynakları üzerinde kontrol kaybediliyor; manifest kaynaklı hash değişiklikleri otomatik yönetilmeli.

---

# 2. Karar (Decision)

1. **Manifest yükleme**  
   - Shell, açılışta gateway’den versiyonlanmış manifest JSON’unu çeker. Manifest şeması CI’da doğrulanır (bkz. ADR-007).
   - Manifest; remote adını, URL’yi, SRI hash’ini, desteklenen semver aralığını ve opsiyonel metadata’yı içerir.

2. **SRI + CSP**  
   - Her `remoteEntry.js` dosyası manifestteki Subresource Integrity hash’i ile doğrulanır; uyuşmazlıkta remote mount edilmez.  
   - CSP `script-src 'strict-dynamic'` moduna alınır; yalnız manifestte beyan edilen kaynaklar izinli olur. CSP report-only evresi ADR-010’da tanımlanacaktır.

3. **Semver uyumluluğu / fail-closed**  
   - Manifestteki sürüm aralığı Shell’in host sürümüyle uyuşmazsa remote yüklenmez (fail-closed).  
   - Remoteler semantik versiyonlama kurallarına uymak zorundadır; breaking değişiklik major sürüm gerektirir.

---

# 3. Alternatifler (Alternatives)

Bu ADR için alternatif çözümler ayrı bir başlık altında dökümante edilmemiştir.  
Gerektiğinde gelecekteki revizyonlarda bu bölüm genişletilebilir.

---

# 4. Gerekçeler (Rationale)

Kararın gerekçeleri büyük ölçüde Bağlam ve Karar bölümlerinde açıklanmıştır:  
- Güvenli manifest yönetimi ve tedarik zinciri risklerinin azaltılması  
- SRI + CSP ile remote kaynakların kontrol altına alınması  
- Semantik versiyonlama ile uyumsuz sürümlerin engellenmesi  

---

# 5. Sonuçlar (Consequences)

- Manifest/artefact üretimi için hash hesaplama, imzalama ve dağıtım pipeline’ı kurulmalı.  
- Shell açılışında ek bir HTTP isteği yapılır (~200 ms hedef). Manifest cache (ETag/TTL) ADR-007/013 ile yönetilecek.  
- Sürüm uyuşmazlıkları dağıtım sırasında erken saptanır; semver disiplini zorunlu hale gelir.

### Acceptance / Metrics

- Manifest doğrulama süresi (p95) 200 ms altında kalır.  
- SRI doğrulaması başarısız olduğunda remote yüklenmez ve kullanıcıya/log’a uyarı düşer.  
- Uyumlu olmayan sürümlerde bloklama oranı %100; canary aşamasında otomatik rollback tetiklenir (ADR-006).

---

# 6. Uygulama Detayları (Implementation Notes)

- Manifest şema doğrulaması CI pipeline’ında zorunlu adımdır.  
- CSP ve SRI konfigürasyonları gateway / CDN katmanında yönetilir.  
- Versiyonlama ve hash yenileme süreçleri release pipeline’larında tanımlanmalıdır.

---

# 7. Durum Geçmişi (Status History)

| Tarih       | Durum    | Not                                   |
|-------------|----------|----------------------------------------|
| 2025-11-02  | Accepted | İlk sürüm, ADR-001 kararı kabul edildi |

---

# 8. Notlar

- Gelecekte manifest modeli (ADR-002) veya canary stratejisi (ADR-006) değişirse bu ADR yeniden gözden geçirilmelidir.
