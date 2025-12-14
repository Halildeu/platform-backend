# Story E08-S01 – Permission Registry v1.0

- Epic: E08 – Permission Registry  
- Story Priority: 810  
- Tarih: 2025-11-29  
- Durum: In Progress

## Kısa Tanım

Permission anahtarları için tek bir registry oluşturup manifest/PageLayout ve CI contract testleriyle entegre ederek erişim kontrolünün şeffaf ve yönetilebilir hale getirilmesi.

## İş Değeri

- Access modülü, rota guard’ları ve UI bileşenleri için permission anahtarları tek kaynaktan yönetilir.
- Deprecate edilen permission’lar takvimli olarak sistemden temizlenir; “ölü” izinler azalır.
- Audit ve denetim süreçlerinde hangi izinlerin nerede kullanıldığı kolayca raporlanır.

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E08-S01-PERMISSION-REGISTRY-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`  
- ADR: ADR-012 (Permission Registry)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- Permission registry şemasının tanımı (JSON/YAML): key, açıklama, sahip, durum, sunset tarihi vb.
- Backstage plugin veya benzeri bir UI ile permission listesinin görünür hale getirilmesi.
- Manifest/PageLayout tarafında permission key’lerinin registry üzerinden doğrulanması ve CI contract testine eklenmesi.
- Deprecate edilen permission’lar için sunset planı ve uyarı mekanizmalarının (ör. UI tooltip) tanımlanması.

### Out of Scope
- Tüm legacy servislerin permission modelinin bir anda registry’e taşınması (kademeli migrasyon).
- Organizasyonel rol/izin tasarımı (security/compliance ekiplerinin alanıdır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Permission registry şemasının tasarlanması (JSON/YAML)      | 2025-11-29   | 2025-11-29    | 2025-11-29   | 2025-11-29  |
| Registry için API ve/veya Backstage plugin UI’ının geliştirilmesi|          |               |              |             |
| Manifest/PageLayout tarafında registry doğrulama entegrasyonunun eklenmesi| 2025-11-29 | 2025-11-29    |              |             |
| Registry dışı permission key kullanımını engelleyen CI kontrollerinin yazılması| 2025-11-29 | 2025-11-29    |              |             |
| Deprecate edilen izinler için sunset/süreç akışının tanımlanması|           |               |              |             |
| Access/Audit ekranlarının registry meta verisini gösterebilmesi| 2025-11-29 | 2025-11-29    | 2025-11-29   | 2025-11-29  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Registry dışı permission key kullanımı CI contract testleri tarafından engellenmelidir.
2. Her permission için sahip ve durum bilgisi registry’de tutulmalıdır.
3. Deprecate edilen izinler için sunset tarihi ve aksiyon planı kaydedilmelidir.
4. Access/Audit ekranları registry’deki meta veriyi görüntüleyebilmelidir.

## Non-Functional Requirements

- Registry değişiklikleri versiyonlanmalı ve audit edilebilir olmalıdır.
- Registry erişimi düşük gecikme ile sağlanmalı; okuma operasyonları kritik akışları yavaşlatmamalıdır.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`

## Definition of Done

- [ ] Permission registry acceptance maddeleri sağlanmış olmalı.  
- [ ] Acceptance dosyasındaki checklist (`docs/05-governance/07-acceptance/E08-S01-Permission-Registry.acceptance.md`) tam karşılanmış olmalı.  
- [ ] ADR-012 kararları uygulanmış olmalı.  
- [ ] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına uyumlu olmalı.  
- [ ] Kod review’dan onay almış olmalı.  
- [ ] Registry doğrulama ve CI kontrolleri yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Registry ile manifest/PageLayout entegrasyonu genişledikçe sunset/deprecate süreçleri acceptance tarafında detaylandırılmalıdır.
- `docs/05-governance/permission-registry/permissions.registry.json` dosyası ilk resmi sözlüğü tutar ve `permissions.schema.json` ile şeması garanti edilir.
- `scripts/permissions/validate-permission-registry.mjs` script’i registry ile frontend manifest/permission constant dosyaları arasında contract testi olarak CI’da koşturulmak üzere eklendi (`node scripts/permissions/validate-permission-registry.mjs`).
- Access MFE’de `PermissionRegistryPanel.ui.tsx` bileşeni registry snapshot’ını gösterir; veri `scripts/permissions/generate-frontend-permission-registry.mjs` ile üretilen `permissionRegistry.generated.ts` dosyasından okunur.

## Dependencies

- ADR-012 – Permission Registry.

## Risks

- Registry ile kod arasındaki eşleşmenin zamanla bozulması.
- Permission değişikliklerinin farklı ortamlara tutarsız dağıtılması.

## Flow / Iteration İlişkileri

| Flow ID   | Durum    | Not                                                   |
|-----------|---------|-------------------------------------------------------|
| Flow-03   | Planned | Permission registry şemasının ve temel entegrasyonun ilk dalgası. |
