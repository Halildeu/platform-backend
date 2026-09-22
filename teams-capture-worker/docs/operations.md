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
