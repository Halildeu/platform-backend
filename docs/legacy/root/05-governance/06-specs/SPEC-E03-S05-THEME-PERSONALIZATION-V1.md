# SPEC-E03-S05-THEME-PERSONALIZATION-V1
**Başlık:** Theme Personalization & Custom Themes v1  
**Versiyon:** v1.0  
**Tarih:** 2025-12-07  

**İlgili Dokümanlar:**  
- ADR: docs/05-governance/05-adr/ADR-016-theme-layout-system.md  
- STORY: docs/05-governance/02-stories/E03-S05-Theme-Personalization.md  
- ACCEPTANCE: docs/05-governance/07-acceptance/E03-S05-Theme-Personalization.acceptance.md  
- STYLE GUIDE: docs/00-handbook/NAMING.md  

---

# 1. Amaç (Purpose)
- Tek kaynaklı tema sisteminde global (admin) ve kişisel (user) temaları yönetmek.  
- Kullanıcıya en fazla 3 kişisel tema hakkı vermek; global temaları sadece admin’in özelleştirip yayınlamasına izin vermek.  
- Yeni renk seçici/registry mekanizması ile tüm semantik alanları (surface/text/border/accent/elevation/overlay) değiştirebilir hale getirmek; ERP aksiyon dili bozulmadan.

# 2. Kapsam (Scope)

### Kapsam içi
- Tema tipi ayrımı: GLOBAL (admin) / USER (owner).  
- Registry tabanlı tema editörü (hangi alan, hangi picker, kim oynar).  
- Whitelist alanlarda renk/aks override; CSS var set etme (tokens kalır).  
- Tema limiti (user max 3), fork akışı (global → user).  
- Tema kartlarında mini önizleme (layout bg, panel bg, accent şerit, overlay şeridi).  
- Persist ve resolved tema akışı: profil + localStorage, `/me/theme/resolved` kontratı.

### Kapsam dışı
- Figma tarafında yeni tasarım üretimi.  
- Org/tenant bazlı tema paylaşımı.  
- Otomatik kontrast düzeltme (yalnız uyarı).

# 3. Tanımlar (Definitions)
- Global theme: Admin’e ait, yayınlanmış tema.  
- User theme: Kullanıcıya ait, max 3 adet, baseTheme’den türetilmiş override seti.  
- Registry: Semantik alan → label, cssVars[], editableBy (ADMIN_ONLY | USER_ALLOWED), controlType (color/opacity/radius/motion), description.  
- Overrides: Registry’de izinli alanlar için key-value; CSS var set edilir, token dosyası değişmez.  
- Non-editable alanlar: `action.danger.*`, `status.danger|warning|success` yalnız admin düzenleyebilir; user override edemez.

# 4. Kullanıcı Senaryoları (User Flows)
- Tema seçimi: Kullanıcı global veya kendi temasını seçer → profil + local persist → sayfa yükünde resolved tema uygulanır.  
- Tema oluşturma (user): Base global seçilir → picker ile izinli alanlar override → kaydet (max 3 kontrol).  
- Global tema düzenleme (admin): Registry’de tüm alanlar açılır → publish → tüm kullanıcılar listede görür.  
- Fork: Global tema kartında “Kopyala ve özelleştir” → user tema oluşturulur, global değişmez.

# 5. Fonksiyonel Gereksinimler (FR)
- **FR-TP-01:** Tema tipi GLOBAL/USER olarak saklanır; GLOBAL CRUD/publish yalnız THEME_ADMIN rolünde.  
- **FR-TP-02:** Kullanıcı başına en fazla 3 USER tema; limit hem API hem UI tarafından enforced.  
- **FR-TP-03:** Registry’de USER_ALLOWED alanlar kullanıcı tarafından picker ile değiştirilebilir; ADMIN_ONLY alanlar sadece admin.  
- **FR-TP-04:** Picker tüm semantik alanlarda CSS var set eder; token dosyasına ham renk yazılmaz.  
- **FR-TP-05:** Tema kartları mini önizleme ile layout bg, panel bg, text, accent, overlay gösterir; seçili tema net highlight edilir.  
- **FR-TP-06:** `/me/theme/resolved` veya eşdeğer endpoint seçili global + user overrides’ı merge edip FE’ye tek JSON döner.  
- **FR-TP-07:** ERP action dili (primary/secondary/danger) kullanıcı override’ına kapalı; admin düzenlerken kontrast/uygunluk kontrolü yapılır.  
- **FR-TP-08:** Tema kartları mini önizleme ile layout bg, panel bg, text-primary/secondary, accent buton şeridi, overlay şeridi gösterir.

# 6. İş Kuralları (Business Rules)
- **BR-TP-01:** User theme sadece overrides içerir; baseTheme güncellendiğinde merge edilir, kural dışı alanlar yok sayılır.  
- **BR-TP-02:** Global tema düzenlemesi yayınlanana kadar “draft”; publish sonrası listede “aktif” olarak görünür.  
- **BR-TP-03:** Limit aşıldığında yeni user tema oluşturma reddedilir (409/422); UI butonu kilitlenir.  
- **BR-TP-04:** Kontrast uyarısı: Picker düşük AA/AAA kontrastı tespit ederse uyarı gösterir (bloklamaz).  
- **BR-TP-05:** Fork davranışı: user teması oluşturulurken overrides boş gelir; baseThemeId seçili global temayı gösterir.

# 7. Veri Modeli (taslak)
- `Theme`  
  - Ortak: `id`, `name`, `type` (GLOBAL|USER), `baseThemeId`, `appearance`, `surfaceTone`, `axes {density,radius,elevation,motion}`, `overrides {registryKey: value}`, `createdAt`, `updatedAt`.  
  - GLOBAL ek alanlar: `isGlobal=true`, `version`, `publishedAt`, `activeFlag`, `visibility` (org/tenant opsiyonel).  
  - USER ek alanlar: `ownerUserId`, `isGlobal=false`; max 3 kuralı service + API katmanında enforce edilir.  
  - Davranış: Fork (GLOBAL→USER) overrides={} ile başlar; baseThemeId seçilen global.  
- `ThemeRegistryEntry` (editör/servis sözleşmesi)  
  - `id`, `label`, `group` (surface/text/border/accent/overlay/erpAction/radius/motion), `controlType` (color/opacity/radius/motion), `cssVars[]`, `editableBy` (USER_ALLOWED|ADMIN_ONLY), `description`, `defaultSource` (token path).  
  - Non-editable örnekler: `action.danger.*`, `status.danger|warning|success` (ADMIN_ONLY).

# 8. API Tanımı (taslak)
- `GET /themes?scope=global|user` – liste.  
- `POST /themes` – user tema oluştur (limit kontrol).  
- `PUT /themes/{id}` / `DELETE /themes/{id}` – owner veya admin; type=USER kontrolü.  
- `POST /themes/global` / `PUT /themes/global/{id}` / `POST /themes/global/{id}/publish` – admin.  
- `POST /themes/{id}/fork` – GLOBAL temadan USER tema oluştur (overrides boş).  
- `GET /me/theme/resolved` – seçili tema + overrides birleşmiş halde.  
  - Response (taslak):
  ```json
  {
    "themeId": "string",
    "type": "GLOBAL" | "USER",
    "appearance": "light|dark|high-contrast",
    "surfaceTone": "string",
    "axes": { "density": "...", "radius": "...", "elevation": "...", "motion": "..." },
    "tokens": { "<semantic-id>": "<resolved-value>" }
  }
  ```  
- `PATCH /me/theme` – kullanıcı varsayılan temasını seçer (global veya kendi).

# 9. Validasyon Kuralları
- Theme type enum; limit 3.  
- Overrides yalnız registry whitelist alanlarıyla sınırlı; bilinmeyen alan reddedilir.  
- Renk format: hex/rgba/hsl kabul; parse edilemezse reddet veya uyarı + eski değere dön.  
- Access kontrolü: GLOBAL için admin rolü zorunlu; USER için ownerId eşleşmesi; non-editable token koruması (action.danger/status.* user için yasak).

# 10. Non-Fonksiyonel Gereksinimler (NFR)
- Tek kaynak: figma.tokens.json → generator → CSS var; override yalnız var set eder.  
- Performans: Tema uygulama = CSS var güncellemesi (repaint), component re-render tetiklemez.  
- Güvenlik: ERP action/danger alanları user’a kapalı; admin override’ı için onay/publish akışı.  
- A11y: Focus ring görünür; HC modda AAA; kontrast uyarısı.  
- Test: lint:semantic; picker + limit + resolved tema e2e; API contract tests; visual smoke (tema kartları, popover/modal/drawer renkleri).

# 11. İzlenebilirlik (Traceability)
- Story: docs/05-governance/02-stories/E03-S05-Theme-Personalization.md  
- Acceptance: docs/05-governance/07-acceptance/E03-S05-Theme-Personalization.acceptance.md  
- ADR: docs/05-governance/05-adr/ADR-016-theme-layout-system.md  
- Style guide: docs/00-handbook/NAMING.md

# 12. Uygulama Notları (Implementation Notes)

- Backend (variant-service altındaki theme bounded context)  
  - Mevcut `variant-service` içine yeni bir theme bounded context eklenir; `Theme` ve `ThemeRegistryEntry` veri modeli JPA/DB katmanında uygulanır ve GLOBAL/USER ayrımı (type, owner, baseThemeId, overrides) burada enforce edilir.  
  - `ThemeService` katmanı GLOBAL CRUD/publish (yalnız THEME_ADMIN), USER tema CRUD (ownerId kontrolü, max 3 kuralı) ve fork davranışını (`GLOBAL → USER`, overrides boş) uygular; `resolveThemeForUser(userId)` metodu seçili global + user overrides birleşimini üretir.  
  - `ThemeRegistryService` registry whitelist’ini (`editableBy=USER_ALLOWED|ADMIN_ONLY`) tek kaynak olarak tutar; overrides yalnız bu whitelist’teki alanlar için kabul edilir (non-editable alanlar: `action.danger.*`, `status.danger|warning|success` yalnız admin tarafından değiştirilebilir).

- API Gateway (route + güvenlik)  
  - API Gateway, `theme-service` için `/api/v1/themes/**` ve `/api/v1/me/theme/resolved` rotalarını tanımlar; SPEC’teki uçlar bu prefix altında yayınlanır.  
  - Güvenlik, mevcut JWT/permission modeline göre uygulanır: GLOBAL tema uçları için THEME_ADMIN rolü zorunlu; USER tema uçları ve `/me/theme/resolved` için authenticated kullanıcı ve ownerId kontrolü yeterlidir (bkz. docs/agents/AGENT-SECURITY.md).

- Frontend (Shell + Theme Runtime entegrasyonu)  
  - Shell `ThemeProvider` ilk yüklemede `/api/v1/me/theme/resolved` endpoint’ini çağırır; dönen `appearance`, `surfaceTone` ve `axes { density,radius,elevation,motion }` değerleri `updateThemeAxes` aracılığıyla `data-theme`, `data-density`, `data-radius`, `data-elevation`, `data-motion`, `data-table-surface-tone` ve `data-accent` özniteliklerine yansıtılır.  
  - Tema picker UI’si registry tabanlıdır: `GET /api/v1/theme-registry` (veya eşdeğer) çıktısındaki `USER_ALLOWED` alanlar kullanıcı için, `ADMIN_ONLY` alanlar yalnız admin için düzenlenebilir; picker değişiklikleri figma.tokens.json yerine yalnız semantic CSS var’larına (`overrides` → runtime var set) yazılır.  
  - Kullanıcının tema seçimi profil tarafında API ile (`PATCH /api/v1/me/theme` veya eşdeğeri) persist edilir; localStorage yalnız son seçilen tema kimliğini cache amaçlı kullanır, sayfa yenilendiğinde fallback olarak bu kimlik üzerinden resolved tema yeniden okunur.
