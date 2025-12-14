---
title: "ACCEPTANCE – SEC-VAULT-FAILOVER-01"
story_id: SEC-VAULT-FAILOVER-01
status: done
owner: "@team/backend"
last_review: 2025-11-29
---

# 1. Amaç
Vault fail-fast davranışı için operasyonel stratejiyi ve FE’ye yansıyan hataları doğrulamak.

# 2. Traceability (Bağlantılar)
- Story: `docs/05-governance/02-stories/SEC-VAULT-FAILOVER-01-Vault-Failover-Strategy.md`
- ARCH: `backend/docs/01-architecture/01-system/01-backend-architecture.md`
- STYLE: `docs/00-handbook/STYLE-BE-001.md`
- PROJECT_FLOW: SEC-VAULT-FAILOVER-01

# 3. Kapsam (Scope)
Vault erişilemezken servislerin davranışı, FE hata yüzeyi, monitoring/alert ve runbook.

# 4. Kabul Kriterleri
- [x] Vault down senaryosu belgelendi: `docs/01-architecture/01-system/01-backend-architecture.md` + `vault-failfast-fallback.md` drill’i servis start/response davranışını ve manuel testi açıklar.  
- [x] FE’ye dönen hata yüzeyi anlaşılır: Auth API dokümanı 503 `vault_unavailable` payload + maintenance banner tetikleyicisini kayıt altına aldı.  
- [x] Monitoring/alert kuralları tanımlı; `Security_Vault_Failfast_Restarts` alert’i ve runbook referansı eklendi.  
- [x] Vault erişimi geri geldiğinde servislerin normal çalıştığı doğrulanmıştır (drill raporu); 2025-11-29 16:40 (UTC+3) `SPRING_PROFILES_ACTIVE=prod ./mvnw -pl auth-service spring-boot:run` komutu sonrası `curl -f http://localhost:8088/actuator/health` 200 döndürdü ve Gateway üzerinden aynı uç 401 (auth beklenen davranış) verdi.  
- [x] PROJECT_FLOW ve session-log günceldir; Story “Review/Done” aşamasına taşınmıştır.  
- [x] Loglarda Vault token/secret sızıntısı yoktur; runbook §8’de paylaşılan drill logu yalnızca bağlantı hatasını ve `VaultConfigDataLoader` stack trace’ini içerir, secret değerleri maskelidir.  
- [x] Gateway/servis loglarında Vault bağlantı hataları korelasyonlu traceId ile görülebilir; `docker compose logs api-gateway` içinde `Vault fail-fast fallback devreye alındı … traceId=drill-001` satırı ve FE’ye dönen `meta.traceId=drill-001` payload’ı eşleşmektedir.

# 5. Notlar
- Prod/QA ortamları için alarm eşikleri SRE ile netleştirilecektir.
