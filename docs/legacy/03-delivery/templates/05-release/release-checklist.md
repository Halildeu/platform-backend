---
title: "Release Checklist - {{ version }}"
status: draft
owner: "@team/release"
release_window: YYYY-MM-DD
---

## 1. Ön Hazırlık
- [ ] Roadmap / changelog güncellendi
- [ ] Özellik bayrakları listelendi (açık/kapalı)
- [ ] TMS sözlükleri (`npm run i18n:pull`) çekildi ve PR merge edildi
- [ ] Pseudolocale smoke CI yeşil

## 2. Test & Doğrulama
- [ ] Regression suite / e2e sonuçları
- [ ] Performans / güvenlik taramaları yeşil
- [ ] İzleme dashboard’ları release öncesi snapshot alındı

## 3. Release Adımları
- [ ] Prod tag/branch hazırlandı
- [ ] Artefact publish / deploy komutları
- [ ] Config / secret değişiklikleri uygulandı

## 4. Yayın Sonrası
- [ ] Smoke test (prod) geçti
- [ ] Telemetry, error rate, loglar izlendi (X saat)
- [ ] Release notu / müşteriye duyuru yayınlandı

## 5. Geri Dönüş (Rollback) Planı
- [ ] Rollback komutu/testi
- [ ] Veri migration geri alma stratejisi

## 6. Lessons & Follow-up
- [ ] Kapanmamış task var mı?
- [ ] Sonraki flow için notlar
