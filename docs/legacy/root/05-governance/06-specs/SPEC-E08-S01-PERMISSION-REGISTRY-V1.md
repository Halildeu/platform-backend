# SPEC-E08-S01-PERMISSION-REGISTRY-V1
**Başlık:** Permission Registry v1.0  
**Versiyon:** v1.0  
**Tarih:** 2025-11-19  

**İlgili Dokümanlar:**  
- EPIC: `docs/05-governance/01-epics/E08_PermissionRegistry.md`  
- ADR:  
  - `docs/05-governance/05-adr/ADR-012-permission-registry.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`  
- STORY: `docs/05-governance/02-stories/E08-S01-Permission-Registry.md`  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`  

---

# 1. Amaç (Purpose)

Permission anahtarlarını tek bir registry üzerinden yönetmek; manifest/PageLayout ile entegrasyonunu ve lifecycle/deprecation modelini tanımlamak (ADR-012).

---

# 2. Kapsam (Scope)

### Kapsam içi
- Permission registry formatı (JSON/YAML) ve storage modeli.  
- Manifest/PageLayout tarafında permission key doğrulaması.  
- Deprecation metadata’sı (active/deprecated) ve sunset planı.  

### Kapsam dışı
- Her domain için detaylı permission politikaları (ayrı Story’lerde ele alınır).  

---

# 3. Tanımlar (Definitions)

- **Permission Key:** `module:resource:action` formatında yetki anahtarı.  
- **Registry:** Tüm permission key’lerinin ve metadata’larının tutulduğu tek kaynak.  

---

# 4. Kullanıcı Senaryoları (User Flows)

1. Yeni bir permission tanımlandığında registry’de kayıt açılır; manifest ve Access module bu key’i kullanır.  
2. Permission deprecate edildiğinde registry’de işaretlenir; UI ve backend guard’ları kademeli olarak temizlenir.  

---

# 5. Fonksiyonel Gereksinimler (Functional Requirements)

**FR-PERM-01:** Permission key’leri yalnız registry’de tanımlı anahtarlar arasından seçilmelidir; registry dışı kullanım CI’da engellenmelidir.  
**FR-PERM-02:** Manifest/PageLayout tanımlarındaki permission alanları registry ile contract testleriyle doğrulanmalıdır.  

---

# 6. İş Kuralları (Business Rules)

**BR-PERM-01:** Deprecate edilen permission’lar için sunset tarihi ve sahip ekip belirtilmelidir.  

---

# 7. Veri Modeli (Data Model)

Permission registry kaydı (örnek):

| Alan      | Tip     | Açıklama                             |
|-----------|---------|--------------------------------------|
| key       | string  | `module:resource:action`            |
| status    | string  | active / deprecated                 |
| owner     | string  | Sorumlu ekip                        |
| sunsetAt  | string  | ISO tarih, varsa                    |

---

# 8. API Tanımı (API Spec)

Registry yönetimi için internal admin API veya Backstage entegrasyonu; detaylar ileriki fazlarda netleştirilecektir.

---

# 9. Validasyon Kuralları (Validation Rules)

- Permission key’leri regex ile doğrulanmalıdır: `^[a-z0-9]+:[a-z0-9-]+:[a-z0-9-]+$`.  

---

# 10. Hata Kodları (Error Codes)

Bu SPEC yeni hata kodu seti tanımlamaz; domain auth/guard katmanları mevcut kodlarını kullanır.

---

# 11. Non-Fonksiyonel Gereksinimler (NFR)

- Registry değişiklikleri versiyonlanmalı ve audit log’a yazılmalıdır.  

---

# 12. İzlenebilirlik (Traceability)

Bu spekten türeyen dokümanlar:
- Story: `docs/05-governance/02-stories/E08-S01-Permission-Registry.md`  
- Acceptance: `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`  
- ADR: ADR-012  

Bu doküman, Permission Registry v1.0 için teknik tasarımın tek kaynağıdır.

