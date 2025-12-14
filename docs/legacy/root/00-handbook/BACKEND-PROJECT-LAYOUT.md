## Backend Monorepo Yapı İlkeleri (LEGACY)

Uyarı: Bu doküman `backend/docs/legacy` altında tutulur ve yalnızca tarihçe /
referans amaçlıdır. Güncel ve kanonik backend layout dokümanı:
- `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md`

Bu dosya, eski backend monorepo içindeki servis klasör yapısını ve yeni servis
için hangi dokümanların oluşturulacağını tanımlar.

- Bu doküman artık **kanonik kaynak değildir**; backend layout ile ilgili yeni
  özetler `docs/00-handbook/BACKEND-PROJECT-LAYOUT.md` dosyasına referans verir.
- Kapsam: `backend/` altında Spring Boot servisleri ve ortak dizinler.
- Kod kalitesi/stil: `docs/00-handbook/STYLE-BE-001.md`
- Mimari yerleşim: servis mimarisi `docs/01-architecture/01-system/01-backend-architecture.md`

### Kök yapı
```
backend/
  api-gateway/
  auth-service/
  discovery-server/
  user-service/
  permission-service/
  variant-service/
  mail-service/
  file-service/
  notification-service/
  docs/
  scripts/
  infra/
```

Her servis bağımsız Spring Boot uygulamasıdır (`pom.xml`); gateway/discovery cross‑cutting bileşenlerdir.

### Servis içi iskelet
```
<service>/
  src/main/java/.../
    controller/
    service/
    repository/
    domain/
    dto/
    config/
    utils/
  src/test/java/.../
```
- Akış: `Controller -> DTO -> Service -> Repository`; business logic controller’a girmez.
- Validasyon DTO seviyesinde yapılır.

### Yeni servis doküman seti
- Servis dokümanı: `docs/01-architecture/03-services/<service>.md`
- API sözleşmesi: `docs/03-delivery/api/<domain>.api.md`
- Runbook (gerekiyorsa): `docs/04-operations/01-runbooks/<xx>-<domain>-runbook.md`
- Story/Spec/Acceptance linkleri: `docs/05-governance/PROJECT_FLOW.md` + `06-specs/` + `07-acceptance/`

### Kalite ve güvenlik kontrolleri
- `STYLE-BE-001` ve backend mimarisindeki advancedFilter/sort/search, logging, security, test kriterlerine uy.
- Güvenlik/compliance dokümanları: `docs/agents/**/*`

Bu rehber backend klasör yapısı için kanonik kaynaktır; mimari/Story/Acceptance referansları buraya işaret eder.
