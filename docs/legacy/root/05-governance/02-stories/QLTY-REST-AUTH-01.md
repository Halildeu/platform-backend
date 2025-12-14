title: "QLTY-REST-AUTH-01 – Auth REST/DTO v1 geçişi"
status: done
owner: "@team/backend"
last_review: 2025-11-29
---

## Amaç
- Auth servisinde REST path v1 (`/api/v1/auth/*`) ve ...Dto isimlendirmesini devreye almak, legacy uçları deprecated etmek.

## Kapsam
- Yeni endpoint'ler: `/sessions`, `/registrations`, `/password-resets`, `/password-resets/{token}`, `/email-verifications/{token}`.
- DTO'lar `...Dto` sonekiyle; hata modeli `ErrorResponse`.
- Doküman güncellemesi: `docs/03-delivery/api/auth.api.md`.

## Durum
- Kod: DONE — AuthControllerV1 ile tüm `/api/v1/auth/*` uçları üretimde, legacy uçlar deprecated modda korunuyor.
- Doküman: DONE (`docs/03-delivery/api/auth.api.md` v1/legacy ayrımı ve ErrorResponse şemasını içeriyor).
- Test: DONE — `AuthControllerV1Test` + `mvn -pl auth-service test` (2025-11-29 15:33 TRT) yeşil; MockMvc validasyon/success akışlarını ve ErrorResponse çıktısını doğruluyor.

## Acceptance (özet)
- Yeni v1 uçları FE’den çağrılabilir; legacy uçlar çalışır ama deprecated olarak işaretlenmiş.
- Yanıtlar ErrorResponse şemasına uygundur; traceId meta taşınır.
- API dokümanı v1/legacy ayrımını içerir.

## Bağlantılar
- `docs/03-delivery/api/auth.api.md`
- `docs/01-architecture/01-system/01-backend-architecture.md`
