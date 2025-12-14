# SPEC-E04-S01-PLATFORM-MANIFEST-V1
**Başlık:** Platform Manifest & Sözleşme v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E04_PlatformManifestAndContracts.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-001-remote-manifest-and-sri.md`  
  - `docs/05-governance/05-adr/ADR-002-page-layout-and-manifest-model.md`  
  - `docs/05-governance/05-adr/ADR-007-contract-and-schema-gating.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

Remote manifest & SRI stratejisini (ADR-001), PageLayout & Manifest modelini (ADR-002) ve Contract & Schema Gating kararını (ADR-007) tek bir teknik tasarım altında birleştirmek.  
Bu spesifikasyon:
- Shell’in manifest JSON’unu nasıl yükleyeceğini ve doğrulayacağını,
- RemoteEntry’ler için SRI + CSP entegrasyonunu,
- Manifest ve ShellServices sözleşmelerinin JSON Schema / TypeScript tipleriyle nasıl versiyonlanacağını,
- CI contract test adımlarının nerede ve nasıl çalışacağını
tanımlar.

---

# 2. Kapsam (Scope)

### Kapsam içi
- Gateway üzerinden versiyonlanmış manifest JSON’unun servis edilmesi.
- Manifest şeması ve ShellServices tip sözleşmelerinin tanımı ve versiyonlanması.
- Shell açılışında manifest yükleme ve doğrulama akışı.
- SRI doğrulamasının ve CSP ayarlarının manifest tabanlı yönetimi.
- Contract test adımlarının CI/CD pipeline’ına entegrasyonu.

### Kapsam dışı
- Canary rollout ve trafik yönlendirme ayrıntıları (ADR-006 / E05 kapsamı).
- DR/HA topolojisi ve multi-region stratejisi (ADR-013 kapsamı).
- Tüm FE ekranlarını bir seferde manifest’e taşıma (kademeli migration ayrı Story’lerde ele alınır).

---

# 3. Tanımlar (Definitions)

- **Manifest:** Shell’in runtime’da yüklediği, remote MFE’lerin ve sözlüklerin meta bilgisini içeren JSON dokümanı.
- **RemoteEntry:** Mikro-frontend’in yüklediği ana bundle (remoteEntry.js).
- **SRI (Subresource Integrity):** Remote script dosyalarının hash ile doğrulanması mekanizması.
- **ShellServices:** Remotelar tarafından kullanılan, shell’in expose ettiği servis sözleşmeleri (auth, i18n, telemetry vb.).
- **Contract Test:** Manifest ve ShellServices tiplerinin, build sırasında karşılıklı uyumluluğunu doğrulayan otomatik testler.

---

# 4. Kullanıcı Senaryoları (User Flows)

1. **Shell Açılışı & Manifest Yükleme**
   - Shell açıldığında gateway’den versiyonlanmış manifest JSON’unu çeker.
   - Manifest JSON Schema’ya göre doğrulanır; hatada shell açılışı fail-closed davranır.

2. **Remote Yükleme & SRI Doğrulama**
   - Manifestte listelenen her `remoteEntry.js` için SRI hash’i kontrol edilir.
   - Hash uyuşmazsa ilgili remote mount edilmez ve kullanıcıya/log’a uyarı yazılır.

3. **Manifest / ShellServices Contract Testleri (CI)**
   - CI’da manifest ve ShellServices tipi, versiyonlanmış şema paketlerine göre doğrulanır.
   - Şema uyumsuzluğu halinde build fail olur; prod’a çıkmadan önce hata yakalanır.

4. **Manifest Güncelleme**
   - Yeni remote veya alan eklenirken şema ve manifest birlikte güncellenir.
   - Semver kurallarına göre manifest sürümü güncellenir (bkz. ADR-001, ADR-007).

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-MANIFEST-01:** Shell açılışında manifest tek bir HTTP isteği ile yüklenmeli; p95 doğrulama süresi 200 ms altında kalmalıdır.  
**FR-MANIFEST-02:** Manifest JSON’u, versiyonlanmış JSON Schema’ya göre valide edilmeden kullanılmamalıdır.  
**FR-MANIFEST-03:** Her remoteEntry için manifestte SRI hash’i bulunmalı ve yükleme sırasında doğrulanmalıdır.  
**FR-MANIFEST-04:** Manifest, remote adı, URL, SRI hash’i, desteklenen semver aralığı ve meta alanlarını zorunlu alanlar olarak içermelidir.  
**FR-MANIFEST-05:** Manifest ve ShellServices tip paketleri CI contract test adımında doğrulanmalıdır; uyumsuzlukta build fail olmalıdır.  

---

# 6. İş Kuralları (Business Rules)

**BR-MANIFEST-01:** Manifest şeması ile runtime’daki manifest JSON’u bire bir uyumlu olmalıdır; opsiyonel alanlar için varsayılan değerler açıkça tanımlanmalıdır.  
**BR-MANIFEST-02:** Manifest sürüm aralığı (semver) host shell sürümüyle uyumsuzsa remote yüklenmez (fail-closed).  
**BR-MANIFEST-03:** SRI doğrulaması başarısız olan remote hiç bir koşulda mount edilmez.  
**BR-MANIFEST-04:** Contract test adımı atlanamaz; pipeline konfigürasyonu bu adımı optional hale getiremez.  

---

# 7. Veri Modeli (Data Model)

## 7.1. Manifest JSON Şeması (Özet)

```json
{
  "version": "1.0.0",
  "remotes": [
    {
      "name": "identity-shell",
      "entryUrl": "https://cdn.example.com/identity/remoteEntry.js",
      "sri": "sha384-...",
      "semverRange": ">=1.0.0 <2.0.0",
      "meta": {
        "module": "IDENTITY",
        "description": "Identity shell MFE"
      }
    }
  ]
}
```

## 7.2. Contract Tip Paketi (ShellServices)

Yüksek seviye tip örneği:

```ts
export interface ShellServices {
  auth: {
    getToken(): Promise<string | null>;
    onTokenChange(cb: (token: string | null) => void): void;
  };
  i18n: {
    t(key: string, params?: Record<string, any>): string;
  };
}
```

---

# 8. API Tanımı (API Spec)

## 8.1. Manifest Endpoint
- Method: `GET`  
- Path: `/api/manifest/v1`  
- Auth: Internal-only (gateway → shell) veya public read-only (gereksinime göre)  
- Content-Type: `application/json`  

### Response Body (200)

Manifest şemasına uygun JSON (bkz. 7.1).

### Error Responses

- `500 INTERNAL_ERROR` – Manifest üretim veya okuma hatası.
- `503 SERVICE_UNAVAILABLE` – Manifest kaynağı geçici olarak ulaşılamıyor.

---

# 9. Validasyon Kuralları (Validation Rules)

- `version`: Semver formatında zorunlu alan.  
- `remotes[*].name`: Boş olamaz, benzersiz olmalıdır.  
- `remotes[*].entryUrl`: Geçerli URL olmalıdır (https).  
- `remotes[*].sri`: SRI formatına uygun olmalıdır (`sha384-...`).  
- `remotes[*].semverRange`: Geçerli semver aralığı string’i olmalıdır.  

---

# 10. Hata Kodları (Error Codes)

| Kod                  | HTTP | Açıklama                                      |
|----------------------|------|-----------------------------------------------|
| MANIFEST_SCHEMA_ERR  | 500  | Manifest json şema doğrulamasında hata       |
| MANIFEST_LOAD_ERR    | 500  | Manifest kaynağı yüklenemedi                 |
| MANIFEST_SRI_MISMATCH| 500  | SRI hash uyuşmazlığı (remote yüklenmedi)     |

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

Performans:
- Manifest doğrulama p95 ≤ 200 ms, toplam açılış etkisi kabul edilebilir TTFA bütçesi içinde kalmalıdır.

Güvenlik:
- Manifest endpoint’i yalnız yetkili bileşenler tarafından değiştirilebilir; write operasyonları audit’e yazılmalıdır.
- SRI hash’leri güvenli build pipeline’ında üretilip versiyonlanmalıdır.

Operasyon:
- Manifest değişiklikleri için rollout ve rollback prosedürleri runbook’larda tanımlanmalıdır.

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E04-S01-Manifest-Platform-v1.md`  
- Acceptance: `docs/05-governance/07-acceptance/E04-S01-Manifest-Platform-v1.acceptance.md`  
- ADR: ADR-001, ADR-002, ADR-007  

Bu doküman, manifest & sözleşme platformu v1.0 için teknik tasarımın tek kaynağıdır.
