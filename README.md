# Backend Mikroservislerine Giris

Bu depo, Spring Boot 3.2 ve Java 21 kullanilarak gelistirilmis mikro servis tabanli bir arka uc mimarisini saklar. Her servis kendi Maven projesi olarak ayakta durur ve Eureka ile servis kesfi, Postgres ile kalici katman ve Docker Compose ile orkestrasyon kullanir.

## Dizinin Yapisi

- `api-gateway`: Trafik yonetimi ve servisler arasi gecis icin Spring Cloud Gateway uygulamasi.
- `auth-service`: Kimlik dogrulama, token uretimi ve kullanici oturum yonetimi.
- `user-service`: Kullanici profili, roller ve iliskili CRUD islemleri.
- `variant-service`: Urun varyantlarini ve bagimli verileri yoneten servis.
- `permission-service`: Yetkilendirme, rol ve izin kontrolleri.
- `core-data-service`: Sirket ana veri servisi.
- `file-service`, `mail-service`, `notification-service`: Yardimci servisler (dosya, e-posta, bildirim).
- `discovery-server`: Eureka servis kesfi sunucusu.
- `docker-compose.yml`: Gelistirme ortaminda tum servisleri hizlica calistirmak icin kompozisyon tanimi.

### Dokümantasyon ve Governance

- `docs/00-handbook`: Backend/Frontend entrypoint, isimlendirme, workflow ve genel mimari sozlesmeler.
- `docs/01-architecture`: Sistem/servis/data mimarisi ve guncel tasarim dokumanlari.
- `docs/05-governance`:
  - Yol haritasi ve mimari kararlar icin tek kok klasor.
  - `PROJECT_MASTER.md` / `SPRINT_INDEX.md` + `epics/`, `stories/`, `sprints/` altinda Epic/Story/Sprint ve oncelik (EP/SP/SrP/TP) sozlesmesi.
  - `03-adr/ADR-0xx-*.md` altinda Architecture Decision Record’lar (neden boyle yaptik?).

## Frontend Çalışma Dizini

- Mikrofrontend uygulamalari bu repo icinde `web/` altinda toplanmistir.
- Tum React/MFE gelistirmelerini, build/test komutlarini ve dokumanlarini `web/` dizini altinda yuruttugunuzdan emin olun.

## Calistirma Adimlari

1. `.env` dosyasinda veya ortam degiskenlerinde Postgres ve servis ayarlarinin tanimli oldugundan emin olun.
2. Taban bagimliliklari icin `docker compose up -d postgres-db discovery-server keycloak vault` veya tek komutla `./scripts/run-services.sh` kullanin.
3. Tum Docker stack'ini ayaga kaldirmak icin: `docker compose up --build`.
4. `./scripts/run-services.sh` akisi su servisleri Maven ile baslatir: `discovery-server`, `auth-service`, `permission-service`, `user-service`, `variant-service`, `core-data-service`, `api-gateway`.
5. Ayni script, oncesinde `postgres-db`, `keycloak`, `vault`, `vault-unseal` compose servislerini otomatik ayaga kaldirir. Gerekirse `AUTO_START_INFRA=0 ./scripts/run-services.sh` ile bu davranisi kapatabilirsiniz.
6. Script ve Maven wrapper'lari varsayilan olarak JDK 21 arar. `JAVA21_HOME` veya genel `JAVA_RUNTIME_TARGET` ile override edebilirsiniz; helper macOS `java_home` yaninda Homebrew `openjdk@21` kurulumunu da tarar. Hedef JDK bulunamazsa mevcut `JAVA_HOME` ya da `PATH` uzerindeki `java` kullanilir ve bu fallback acikca loglanir.
7. `./scripts/run-services.sh` her yeni kalkista taze bir startup session kaydi yazar ve varsayilan olarak runtime guard calistirir. Guard tum backend servislerinin `/actuator/health` durumunu, `postgres-db` / `keycloak` / `vault` erisilebilirligini ve yeni log penceresindeki `WARN` / `ERROR` sinyallerini tarar. Rapor yolu: `.cache/reports/backend_runtime_guard.v1.json`.
8. Sadece startup tetigi isteniyorsa `RUN_SERVICES_POSTCHECK=0 ./scripts/run-services.sh` kullanabilirsiniz. Lane seviyesinde ayni guard strict warning modunda `cd backend && ./scripts/health/run-runtime-lane.sh` ile temiz baslangic + otomatik kapanis davranisiyla kosar.
9. Docker Compose full stack icin `./scripts/run-compose-stack.sh` ayni startup/session mantigini compose servislerine tasir. Wrapper tum stack'i ayaga kaldirir, `.cache/runtime_guard/backend_compose_session.v1.json` kaydini yazar ve varsayilan olarak `.cache/reports/backend_compose_runtime_guard.v1.json` raporunu uretir.
10. Compose lane strict warning modunda `cd backend && ./scripts/health/run-compose-runtime-lane.sh` ile kosar; temiz `docker compose down` ile baslar ve cikista tum stack'i indirir. `BACKEND_RUNTIME_MODE=compose python3 ci/run_module_delivery_lane.py --lane integration` ile integration lane bu compose guard'a yonlenir.
11. `python3 ci/run_module_delivery_lane.py --lane <lane>` artik varsayilan olarak temiz `stderr` bekler; bu default `ci/module_delivery_lanes.v1.json` icindeki `clean_stderr_default=true` alanindan okunur. CI ile yerel davranis aynidir. Gecici ve bilincli bir istisna gerekirse yalniz o kosu icin `--allow-stderr` kullanabilirsiniz.
12. Ham `./mvnw ... test|verify|install|package` cagrilari artik wrapper seviyesinde benzersiz `-DtempDir` / `-Dsurefire.reportNameSuffix` enjekte eder; boylece ayni anda calisan backend test kosulari `common-auth` gibi upstream modullerde surefire cakismasi uretmez. Gerekirse `CODEX_SUREFIRE_ISOLATION_DISABLE=1` ile bu davranis kapatilabilir.
13. `unit` lane varsayilan olarak izole backend matrix kosar: `user-service`, `api-gateway`, `variant-service`, `core-data-service`. `python3 ci/run_module_delivery_lane.py --lane unit` env vermeden bu subset'i paralel calistirir. Farkli liste icin `BACKEND_TEST_MATRIX="user-service auth-service"` kullanabilir, tam eski davranis icin `BACKEND_TEST_MATRIX=off` ile tek akisa donebilirsiniz.
14. Derleme/rebuild asamasinda warning/error guard icin `./scripts/health/run-backend-build-guard.sh -- -q -DskipTests compile` kullanabilirsiniz. Varsayilan rapor yolu `.cache/reports/backend_build_guard.v1.json` olup `run_lint_backend.sh` ve `run_tests_api.sh` bu wrapper'i otomatik kullanir.
15. Frontend tarafinda `cd web && npm run build` artik build guard wrapper'ina gider; rapor `.cache/reports/web_build_guard.v1.json` altina yazilir. `contract` lane de lint + test sonrasinda bu build guard'i kosar ve ardindan web runtime lane'e gecer.

### (Opsiyonel) Kullanici Seed: 1200 adet ornek kullanici

Flyway ile `V4__seed_1200_users.sql` otomatik calisir; yine de manuel tetiklemek isterseniz:

- Docker Compose ile (repo kokunden):

  `./scripts/seed-users.sh`

- Alternatif: Dogrudan Docker (container adini kendinize gore ayarlayin):

  `cat user-service/src/main/resources/db/migration/V4__seed_1200_users.sql | docker compose exec -T postgres-db env PGPASSWORD=postgres psql -U postgres -d users -f -`

Kontrol icin:

`docker compose exec postgres-db psql -U postgres -d users -c "select count(*) from users;"`

## Gelistirme Notlari

- Servisler arasi yeniden kullanilacak kodlar artik her bir servisin kendi projesinde tutulmaktadir; gerektiginde bagimli moduller ile paylasim yapilabilir.
- Test altyapisi REST Assured, Testcontainers ve Spring Boot Test ile desteklenir.
- Yeni bir servis eklerken `docker-compose.yml` dosyasini, ortam degiskenlerini ve Eureka kayit ayarlarini guncellediginizden emin olun.
