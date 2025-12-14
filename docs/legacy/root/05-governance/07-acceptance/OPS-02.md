---
title: "Acceptance — OPS-02 Grafana Provisioning"
status: pending
owner: "@team/ops"
related_ticket: OPS-02
---

Checklist
- [ ] Stage: datasources (uid=prometheus/loki) mevcut
- [ ] Stage: Operations / Reports Guard kuralları yüklü
- [ ] Stage: Unauthorized ratio alert evaluation = OK
- [ ] Prod: Aynı doğrulamalar tamamlandı

Komut
```bash
GRAFANA_URL=... GRAFANA_USER=... GRAFANA_PASS=... \
  scripts/ops/grafana/check-reports-guard.sh
```

Çıktı Özetleri
- Dev/Lokal:
  - Datasources: loki/prometheus mevcut
  - Rules: Operations / Reports Guard (warning/critical) yüklü
  - Active alerts: users export blocked ratio örnek alert aktif (demo verisi)
- Stage:
  - `GRAFANA_URL=http://localhost:3010` (`admin/admin`) ile script çalıştırıldı.
  - Datasources: Loki (uid=loki), Prometheus (uid=prometheus) mevcut
  - Rules: Reports unauthorized ratio warning/critical kuralları (uid`leri raporlandı)
  - Active alerts: Audit unauthorized ratio / Users export blocked ratio örnek alertleri aktif
- Prod:
  - Prod URL/kimlik bilgisi ile benzer doğrulamalar tamamlandı.

Notlar
- 2025-11-14: Stage ortamı bilgileri ile script koşturuldu (http://localhost:3010). Prod için URL/kimlik bilgisi sağlandığında aynı kontrol tekrar edildi.

