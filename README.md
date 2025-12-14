# Backend Monolitine Giris

Bu depo, Spring Boot 3.2 ve Java 21 kullanilarak gelistirilmis mikro servis tabanli bir arka uc mimarisini saklar. Her servis kendi Maven projesi olarak ayakta durur ve Eureka ile servis kesfi, Postgres ile kalici katman ve Docker Compose ile orkestrasyon kullanir.

## Dizinin Yapisi

- `api-gateway`: Trafik yonetimi ve servisler arasi gecis icin Spring Cloud Gateway uygulamasi.
- `auth-service`: Kimlik dogrulama, token uretimi ve kullanici oturum yonetimi.
- `user-service`: Kullanici profili, roller ve iliskili CRUD islemleri.
- `variant-service`: Urun varyantlarini ve bagimli verileri yoneten servis.
- `permission-service`: Yetkilendirme, rol ve izin kontrolleri.
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

- Mikrofrontend uygulamalari artik tek bir repoda toplanmistir: `/Users/halilkocoglu/Documents/dev/frontend`. Tum React/MFE gelistirmelerini, build/test komutlarini ve dokumanlarini **yalnizca** bu dizin altinda yuruttugunuzdan emin olun.
- Bu depodaki `frontend/` ve `apps/mfe-users` klasorleri yalnizca arsiv/reference icindir; build/CI pipeline’lari onlari kullanmaz.

## Calistirma Adimlari

1. `.env` dosyasinda veya ortam degiskenlerinde Postgres ve servis ayarlarinin tanimli oldugundan emin olun.
2. Taban bagimliliklari icin Docker yagli degilse calistirin: `docker compose up -d postgres-db`.
3. Servisleri ayaga kaldirmek icin: `docker compose up --build`.
4. Her servisi tek tek calistirmak isterseniz `mvn spring-boot:run` komutunu ilgili servis klasorunde kullanabilirsiniz.

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
