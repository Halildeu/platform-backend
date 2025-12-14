# Audit Event Feed Performans Senaryosu

Bu doküman `mfe-audit` gridinin 100K kayıt yükü altında hedeflenen metrikleri doğrulamak için kullanılacak lokal testi tarif eder.

## Ön Koşullar
- Docker veya lokalde çalışan Postgres örneği.
- `permission-service` için `spring.jpa.hibernate.ddl-auto=update` aktif (default).
- Audit tablosuna 100.000 satır üretecek seed script’i (`scripts/sql/seed-audit-events.sql`) çalıştırılmış olmalı. Seed yoksa aşağıdaki komut örneği kullanılabilir:

```sql
INSERT INTO permission_audit_events(event_type, performed_by, details, user_email, service, level, action, correlation_id, metadata, before_state, after_state, occurred_at)
SELECT
  CASE (random() * 3)::int
    WHEN 0 THEN 'ASSIGN_ROLE'
    WHEN 1 THEN 'UPDATE_ROLE'
    ELSE 'REVOKE_ROLE'
  END,
  (random() * 1000)::int,
  'Synthetic load test record',
  'user:' || (random() * 10000)::int,
  'permission-service',
  CASE (random() * 3)::int
    WHEN 0 THEN 'INFO'
    WHEN 1 THEN 'WARN'
    ELSE 'ERROR'
  END,
  CASE (random() * 3)::int
    WHEN 0 THEN 'ROLE_ASSIGNED'
    WHEN 1 THEN 'ROLE_UPDATED'
    ELSE 'ROLE_REVOKED'
  END,
  md5(random()::text),
  '{"scope":"load-test"}',
  '{}',
  '{}',
  NOW() - (random() * interval '14 days')
FROM generate_series(1, 100000);
```

## Test Adımları
1. Backend servislerini ayağa kaldırın:
   ```bash
   ./mvnw -pl permission-service spring-boot:run
   ```
2. Önceki adımda oluşturulan audit kayıtlarının hazır olduğundan emin olun (`SELECT COUNT(*) FROM permission_audit_events;`).
3. Frontend kök dizininde audit MFE’yi prod build modunda çalıştırın:
   ```bash
   npm run build:audit
   npx serve apps/mfe-audit/dist --listen 4300
   ```
4. Tarayıcıdan `http://localhost:4300/index.html#/audit/events` adresini açın ve DevTools Performance panelinde aşağıdaki metrikleri ölçün:
   - İlk grid sorgusu tamamlanma süresi (TTFA): hedef ≤ 3 sn.
   - İlk render sonrası Time to Interactive: hedef ≤ 2.5 sn.
5. Filtre barından birden fazla filtre kombinasyonu deneyip (örn. `WARN` seviyesi, belirli service adı) toplam 3 ölçüm alın. Ortalama değeri dokümante edin.
6. Ölçüm sonuçlarını `frontend/docs/01-architecture/01-shell/03-audit-event-feed.md` içindeki performans bölümüne ekleyin.

## Sonuç Notları
- Metriği tutturamayan build’lerde grid `PAGE_SIZE` veya backend index’leri gözden geçirilmeli.
- Lighthouse raporlarında Perf puanı < %80 olduğunda CI pipeline’ı başarısız olur (`npm run test:quality`).
