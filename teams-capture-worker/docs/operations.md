# Teams kontrol worker'ı — işletim ve teslim

Bu sürüm bot katılımı, çağrı durumu, tekrar isteği koruması, bağlantı bakımı,
botun çıkışı ve anlık katılımcı listesi içindir. Canlı ses/isimli transkript,
toplantı sırasında karar/aksiyon, Teams yan paneli ve takvim zamanlayıcısı
tamamlanmış değildir. Kullanıcının istediği kabul bunları birlikte gerektirir.

## Kurum ayarları

Mevcut Entra uygulaması tekrar açılmaz. TEST organizatör hesabı bot uygulama
kimliğinden ayrıdır. Kurumun secret yöneticisi aşağıdaki değerleri çalışma
ortamına verir; parola/secret mail, GitHub, telefon veya tarayıcıya gönderilmez.

| Değişken | Anlam |
|---|---|
| `TeamsCapture__Enabled` | Varsayılan false; tenant hazırlığı sonrası operatör açar |
| `TeamsCapture__TenantId` | Kurum tenant UUID |
| `TeamsCapture__ApplicationId` | Mevcut bot client UUID |
| `TeamsCapture__ClientSecret` | Kurum secret yönetiminden uygulama credential değeri |
| `TeamsCapture__ControlApiKey` | En az 32 karakter, yalnız kurum içi kontrol istemcisi |
| `TeamsCapture__PublicCallbackBaseUrl` | Onaylı HTTPS kök adres; endpoint otomatik `/api/teams/callback` |
| `TeamsCapture__CallStateFilePath` | Kişiye/servise özel kalıcı mutlak dosya yolu |
| `TeamsCapture__CalendarStateFilePath` | Aynı kalıcı alanda farklı mutlak dosya yolu |
| `TeamsCapture__CompletedCallRetentionHours` | Kurumun belirlediği 1–2160 saat; yoksa silme kapalı |

Tek replika kullanılır; state dosyaları yerel geçici pod dosyası olmamalıdır.
İki ayar yolu eski JSON okuma konumunu ve yeni SQLite dosyalarının ön ekini
belirler: örneğin `calls.json` için güncel depo `calls.json.sqlite3` olur.
Üst dizin kurulumda oluşturulmuş ve kalıcı olmalıdır; worker dizin oluşturmaz.
SQLite dosyaları ve varsa işlem günlükleri aynı kalıcı volume üzerinde tutulur.
`DELETE` günlük modu ve `EXTRA` senkronlama her bağlantıda doğrulanır.
Volume, SQLite kilitleme/fsync sözleşmesini desteklemelidir; pod diski veya
bu sözleşmeyi sağlamayan ağ dosya sistemi kalıcı kabul edilmez.
Bozuk/yarım SQLite deposu eski JSON'a veya boş duruma otomatik dönmez: yeni
katılım kapalı tutulur, uzaktaki çağrılarla operatör incelemesi yapılır.
Bu sürüm Linux üzerinde kontrol düzlemi olarak çalışır. Aynı paketi Linux'ta
çalıştırmak Microsoft ham medya SDK'sını çalıştırmak anlamına gelmez.

## Teams uygulama paketi

1. `teams-app-manifest/config.example.json` dosyasının repo dışındaki kopyasına
   **onaylı** Teams app UUID, bot client UUID, callback kökü, gizlilik ve kullanım
   koşulu URL'leri girilir. Son iki adresi geliştirici tahmin etmez; kurum sağlar.
2. Repo kökünde `node teams-capture-worker/teams-app-manifest/package.mjs
   APPROVED_CONFIG.json NEW_PACKAGE_DIRECTORY` çalıştırılır. Çıktı hashleri
   paket klasörü dışında teslim kaydına alınır. Yeni klasör gerekir; eski paket
   sessizce değiştirilmez. Şablon/boş alan veya fazladan secret alanı reddedilir.
3. Klasördeki yalnız `manifest.json`, `color.png`, `outline.png` dosyaları ZIP'in
   **köküne** konur. PowerShell: `Compress-Archive -LiteralPath
   NEW_PACKAGE_DIRECTORY/manifest.json,NEW_PACKAGE_DIRECTORY/color.png,NEW_PACKAGE_DIRECTORY/outline.png
   -DestinationPath NEW_TEAMS_PACKAGE.zip`. ZIP'e ayar dosyası veya secret eklenmez.
4. Microsoft şema/Teams yükleme doğrulaması ve kurum yükleme izni ayrıca gerekir.
   Dosya üretimi DNS, URL erişimi, tenant yüklemesi veya yan panel kabulü değildir.
   Paket bilinçli olarak bot-only kalır; uygulanmamış bir yan panel adresi eklenmez.

## Kişisel DEV kontrolü

Kaynak derlemesi kişisel branch/PR üzerinden yürür. DEV README gereği ortak
servislere elle deploy yapılmaz. Özel önizleme yalnız boş `127.0.0.1` portuna
bağlanır. `TeamsCapture__Enabled=false` ile aşağıdaki kontroller yapılabilir:

- `/health`: 200; `disabled-until-tenant-registration` ve `liveAudio=false`.
- Kontrol anahtarı olmadan `/api/teams/readiness`: 401.
- Kimlik olmadan `/api/teams/callback`: 401.
- Kapalı ayarla join: 503, Microsoft çağrısı yapılmaz.

Önizleme test sonunda kendi PID'siyle kapatılır. Bu sonuç gerçek Teams'e
katılma/kayıt izni/çağrı callback kabulü sayılmaz.

## Gerçek tenant denemesi

### Kurulabilir container ve kimlik ön kontrolü

CI önce .NET/Node testlerini çalıştırır, ardından digest ile sabitlenmiş Microsoft
SDK/runtime tabanlarından Linux container üretir. İmaj varsayılan kapalıdır,
1654:1654 kullanıcısıyla çalışır; secret, toplantı içeriği ve tenant ayarı içermez.
CI, salt okunur kök dosya sistemi ve geçici volume ile container'ı gerçekten
başlatır: health, anonim kontrol/callback reddi, kapalı katılım reddi, volume yazma
izni ve restart sonrası test işaretinin korunması doğrulanır. Bu işaret gerçek
Teams çağrı durumu değildir. İmaj kaynak SHA'sı ve imageID, `smoke.json` içindedir.

`teams-worker-container-SHA` artifact'inde `teams-worker-image.tar.gz`,
`SOURCE_COMMIT`, `smoke.json` ve `SHA256SUMS` vardır. Archive Docker image arşividir;
`docker load` ile içeri alınabilir. CI registry'ye push veya ortama deploy yapmaz.
Güvenilen PR CI kaynağı üzerinden SHA256 doğrulandıktan sonra operatör imajı
kurum registry'sine taşır ve **registry digest** değerini canonical GitOps TEST
overlay'ine sabitler. Yerel imageID veya arşiv hash'i registry digest yerine geçmez.
PR CI SHA'sı deneme birleşim commit'idir; `github.sha` ile kayda alınır.

GitOps kurulum sözleşmesi: tek replika, `Recreate` stratejisi, kalıcı
`/var/lib/teams-capture` volume, UID/GID/fsGroup 1654, read-only root filesystem,
yazılabilir `/tmp`, `allowPrivilegeEscalation=false`, drop ALL capabilities,
8080 Service ve `/health` process probe. `/health`, kayıt yetkisi veya gerçek
toplantıya katılım kanıtı değildir. Kapalı worker 200 health dönebilir.
Aktivasyon/secrets yalnız TEST secret store'dan sağlanır. Ortak `k3d-test`
workload'larına doğrudan apply/patch yerine GitOps repo akışı kullanılır.

İnternete yalnız **tam eşleşen** `POST /api/teams/callback` yolu açılır; kontrol
uçları ve `/health` public ingress'e verilmez. Bu sürümün Graph katılım isteği
callback köküne sabit `/api/teams/callback` ekler; farklı dış yol desteklenmez.
Microsoft kaydı, dış ingress ve worker aynı tam yolu kullanmalıdır.
Callback alanını genel `/api/teams` proxy'siyle açmak kontrol API'sini dışarı taşır.
Mevcut `testai.acik.com` alanı kullanılabilir; ayrı DNS gerekli değildir.

Kurum ayarları yerleştirilince operatör, worker'a özel loopback port yönlendirmesi
üzerinden `scripts/preflight.mjs` çalıştırır. `preflight.example.json` yalnız
onaylı açık ayarlarla doldurulur; `TEAMS_CONTROL_API_KEY` süreç ortamına secret
yöneticisinden verilir, komut satırına/yapılandırma JSON'una yazılmaz:

```sh
node teams-capture-worker/scripts/preflight.mjs APPROVED_PREFLIGHT.json NEW_REPORT.json
```

Ön kontrol health, anonim callback/control reddi ve korumalı readiness'teki
**beklenen tenant + uygulama kimliğini** karşılaştırır. Yönlendirmeleri izlemez;
anahtar yalnız özel kontrol adresine gönderilir. Rapor hata gövdesi, secret veya
katılımcı adı içermez. Bu kimlik **yapılandırma kanıtıdır**, Microsoft'ta credential
geçerliliği veya botun gerçekten kimlik doğrulaması yaptığı iddiası değildir.
İlk gerçek Graph katılımı ve Microsoft imzalı callback bu ayrımı tamamlar.
401 callback cevabı tek başına Microsoft bildirimlerinin ulaşacağını kanıtlamaz.

Rollback: aktivasyon başarısızsa yeni katılım durdurulur; bilinen aktif çağrılardan
yetkili leave ile çıkış doğrulanır. Önceki onaylı digest/ayar GitOps üzerinden geri
alınır. PVC ve belirsiz katılım kayıtları silinmez; restart belirsiz katılımı
sıfırlamaz. Bu kaynak paketi bir canlı rollout veya rollback kabulü değildir.
Önceki imajın SQLite durum biçimini desteklemesi gerekir. JSON kullanan eski
imaj, güncel `.sqlite3` rezervasyonlarını okuyamaz; ona doğrudan dönüş yapılmaz.
Önce çağrıların çıkışı/uzlaştırması ve uyumlu durum taşıma planı doğrulanır.

### Toplantı kabulü

Kurum callback, Teams calling kaydı, uygulama izinleri, secret ve kalıcı alanı
hazırladıktan sonra kontrollü TEST toplantısında sırasıyla join → established
callback → roster → 45 dakikadan uzun keepAlive → leave doğrulanır. Aynı join
eşzamanlı ve yeniden başlatma sonrası tekrarlandığında tek bot görülmelidir.

`join_outcome_unconfirmed` oluşursa aynı toplantıyı yeni UUID ile tekrar
oluşturmayın. Çağrı gerçekten oluşturulmuş olabilir. Özel join-status kaydı,
Graph çağrı kaydı ve toplantı katılımcıları yetkili operatörce eşleştirilir.
Bu sürümde belirsiz kayıt için otomatik reset yoktur; kanıt olmadan silinmez.
`join_persistence_failed_cleanup_unconfirmed` ayrıca uzaktaki bot çağrısının
çıktığını doğrulamayı gerektirir. Hazır olmayan kaydı başarılı diye işaretlemeyin.

Canlı medya yöntemi onaylanıp uygulandığında iki konuşmacıyla isim/cümle/zaman
eşleşmesi, yeniden katılma, eşzamanlı konuşma ve **toplantı bitmeden** karar/aksiyon
görünmesi ayrıca test edilir. Ortak mikrofon tek Teams hesabıdır; odadaki gerçek
kişilerin adları yalnız katılımcı listesinden çıkarılamaz.
