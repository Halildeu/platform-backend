---
title: "{{ Service Name }} Runbook"
status: draft
owner: "@team/oncall"
last_review: YYYY-MM-DD
service: "{{ service-id }}"
sla: "99.9%"
---

# 1. Servis Özeti
- Sorumlu ekip / escalation chain
- Prod / stage URL’leri, dashboard linkleri

# 2. SLA / SLO / Error Budget
- SLO tanımı, ölçüm kaynağı
- Alert threshold’ları

# 3. Monitoring
- Dashboard ve log aramaları
- Kritik metrikler ve kabul edilebilir aralık

# 4. Incident Prosedürü
- İlk yapılacaklar (triage checklist)
- Sık görülen alarmlar ve aksiyon adımları
- Rollback / disable adımları

# 5. Bağımlılıklar
- Yukarı/aşağı akış servisleri
- Feature flag / config bağımlılıkları

# 6. Bakım İşleri
- Periyodik görevler (sertifika yenileme, key rotation…)
- Runbook doğrulama planı

# 7. Ekler
- Postmortem linkleri
- Dönev notlar, referans dokümanlar
