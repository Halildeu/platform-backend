---
title: "OpenAPI — Redoc / Swagger UI Entegrasyonu"
status: draft
owner: "@team/platform-docs"
last_review: 2025-11-12
---

Özet
- Bu rehber `user-service.yaml` ve `permission-service.yaml` OpenAPI şemalarını tek sayfalık dokümana dönüştürmek için Redoc ve Swagger UI örneklerini içerir.

Dosyalar
- Şemalar: `docs/03-delivery/api/openapi/user-service.yaml`, `docs/03-delivery/api/openapi/permission-service.yaml`
- Redoc statik örnekler: `redoc-user.html`, `redoc-permission.html`

Redoc (CLI ile hızlı servis)
```bash
# Kurulum gerekmez, npx ile çalışır
npx redoc-cli serve docs/03-delivery/api/openapi/user-service.yaml --port 9091 --watch
npx redoc-cli serve docs/03-delivery/api/openapi/permission-service.yaml --port 9092 --watch

# Statik bundle (tek HTML üretir)
npx redoc-cli bundle docs/03-delivery/api/openapi/user-service.yaml \
  -o docs/03-delivery/api/openapi/user-service.html
npx redoc-cli bundle docs/03-delivery/api/openapi/permission-service.yaml \
  -o docs/03-delivery/api/openapi/permission-service.html
```

Redoc (CDN ile statik HTML)
- Aç: `docs/03-delivery/api/openapi/redoc-user.html`, `redoc-permission.html`
- Basit bir HTTP server ile servis edin (örn. `npx http-server docs/03-delivery/api/openapi -p 9090`).

Swagger UI (Docker ile)
```bash
# user-service için
docker run -p 8088:8080 \
  -e SWAGGER_JSON=/specs/user-service.yaml \
  -v $(pwd)/docs/03-delivery/api/openapi:/specs \
  swaggerapi/swagger-ui

# permission-service için ayrı portta
docker run -p 8089:8080 \
  -e SWAGGER_JSON=/specs/permission-service.yaml \
  -v $(pwd)/docs/03-delivery/api/openapi:/specs \
  swaggerapi/swagger-ui
```

Swagger UI (CDN ile statik HTML)
- `swagger-user.html` veya `swagger-permission.html` (örnek eklenebilir) ile aynı klasörden YAML’a referans verin.

Notlar
- CORS/yerel dosya erişim kısıtlarına takılmamak için bir HTTP server üzerinden servis edin.
- Çoklu şema tek sayfada listelemek için Redocly veya Swagger UI index konfigi kullanılabilir; bu rehber hızlı tek sayfa/doğrudan örnekleri içerir.

