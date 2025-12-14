title: "QLTY-REST-USER-01 – User REST/DTO v1 geçişi"
status: done
owner: "@team/backend"
last_review: 2025-11-29
---

## Amaç
- User servisinde v1 REST path'leri (`/api/v1/users`) ve ...Dto isimlendirmesini devreye almak; legacy uçları deprecated olarak bırakmak.

## Kapsam
- Listeleme, detay, email ile getirme, aktivasyon uçları v1 altında.
- PagedResult zarfı (`items/total/page/pageSize`) ve whitelist’li advancedFilter/sort.
- Doküman güncellemesi: `docs/03-delivery/api/users.api.md`.

## Durum
- Kod: DONE — `/api/v1/users/**` uçları PagedResult + advancedFilter whitelist ile devrede, legacy `/api/users/*` uçları deprecated uyarısı ile açık.
- Doküman: DONE — `docs/03-delivery/api/users.api.md` v1 path, zarf, whitelist ve legacy notlarını içeriyor.
- Test: DONE — `UserControllerV1Test` entegrasyon senaryoları + `mvn -pl user-service test` (2025-11-29 15:34 TRT) JWT doğrulamasını ve tüm CRUD akışlarını geçiyor.

## Acceptance (özet)
- `/api/v1/users` listeleme zarf + parametre sözleşmesine uyar.
- `/api/v1/users/{id}` ve `/by-email` detay döner; `/activation` aktif/pasif ayarlar.
- Legacy `/api/users/*` uçları deprecated notuyla çalışır.
- API dokümanı v1/legacy ayrımını içerir.

## Bağlantılar
- `docs/03-delivery/api/users.api.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
