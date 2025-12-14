---
title: "Acceptance — E06-S01 Telemetry and Observability"
status: pending
related_story: E06-S01
---

Story ID: E06-S01-Telemetry-and-Observability

Checklist
- [ ] Telemetry event şeması ve TTFA ölçümleri devrede; panolarda TTFA ve hata oranı görünüyor.  
  - Not: Telemetry taksonomisi JSON şema + TS tip olarak eklendi (`backend/telemetry/telemetry.schema.json`, `telemetry.types.d.ts`, `frontend/packages/shared-types`), FE trackPageView/trackAction/trackMutation helper’ları `/api/v1/telemetry/events`’e event gönderiyor (mfe-shell route change, users refresh, access role actions/mutations). TTFA ve panolar ortamda bekleniyor.
- [ ] FE→BE trace zinciri Tempo’da uçtan uca en az bir kritik akış için doğrulandı.  
  - Not: FE telemetry event’lerinde traceId, shared-http’deki X-Trace-Id ile eşitleniyor; backend Tempo korelasyonu ortam entegrasyonu sonrası doğrulanacak.
- [ ] `auditId` akışı notification/detail ekranlarından audit ekranına kadar uçtan uca çalışıyor.  
  - Not: Access’te role permission güncelleme mutasyonu trackMutation ile auditId (varsa) ve süre bilgisiyle telemetry’ye iletiliyor; audit ekranına derin link ortamda doğrulanacak.
- [ ] PII redaction ve retention kuralları dokümante edildi ve telemetry pipeline’ına uygulandı.  
  - Not: Şema yorumlarında PII yasak, userId yerine userHash/anonim kimlik kuralı belirtili; redaction uygulaması ortam pipeline’ı ile tamamlanacak.
