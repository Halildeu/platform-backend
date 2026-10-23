# Teams Capture Worker

Bu worker, Teams/Calendar toplantısına katılan botun kontrol düzlemidir. Masaüstü
ve mobil uygulama koduna dokunmaz.

Akış: Teams/Calendar bot katılımı → kullanıcı onaylı mobil/masaüstü recorder →
audio-gateway session → Direct-STT → meeting-ai → meeting-service kanonik sonuç.
Özet, kararlar, aksiyonlar ve görev atamaları worker tarafından üretilmez veya
saklanmaz; yalnız kanonik sonuç okunur.

Bu worker canlı ses almaz. Microsoft, ham medya botlarını AI toplantı ajanları
için önermez; bu yolun ek altyapı gereksinimleri vardır. Bu bir teknik yasak
değildir. Kullanıcının toplantı sırasında ses, konuşmacı adı ve analiz istemesi
için mevcut service-hosted katılım kodu tek başına yeterli değildir. Doğrudan
Microsoft medya bağlantısı ve aracı hizmet seçenekleri ayrı değerlendirilir;
bu kaynak dilimi iki seçenekten birinin canlı medya kabulü değildir.
Katılımcı bilgilendirmesi ve recorder rızası, bot katılımından bağımsızdır.

Gerekli Microsoft tarafı: tenant ve application kimlikleri, HTTPS callback,
`Calls.JoinGroupCall.All` için yönetici onayı ve Teams manifestinde
`supportsCalling=true`. Bu değerler kaynak koda yazılmaz.

## Kaynak uygulama durumu

Güncel kaynak/paket kanıtları, kalan geliştirmeler ve Halil Bey / IT'den
beklenenler: [25 Eylül TEST teslim durumu](docs/test-delivery-status-20260925.md).

Seçilen Outlook etkinliğine zamanlı katılım için kapalı başlayan kalıcı
zamanlayıcı ve gerçek Graph okuma istemcisi eklendi. İptal/saat değişikliği
katılımdan önce yeniden doğrulanır. Kurum izinleri ve gerçek TEST kabulü açık;
yan panelden seçim köprüsü backend #1195 ve web #1198 ile kaynakta bağlandı,
ancak TEST'e kurulmadı. [Kurulum ve API](docs/calendar-scheduling.md).

EntraTeamsAccessTokenProvider, kurum secret yönetiminden sağlanan
`TeamsCapture__ClientSecret` ile tenant-specific client_credentials akışını
uygular. Kapsam sabit `https://graph.microsoft.com/.default`; ek Graph izni
istemez. HTTP yönlendirmeleri kapalıdır; token diske veya loga yazılmaz.
Eksik/kapalı ayarlarla istek göndermez. Microsoft protokolü:
https://learn.microsoft.com/en-us/entra/identity-platform/v2-oauth2-client-creds-grant-flow

Program sağlık, kurum içi katılım komutu ve kimlik doğrulamalı
`/api/teams/callback` uç noktalarını açar. Callback Microsoft'un
Skype OpenID anahtarları, botframework issuer, uygulama audience, süre ve
tenant kontrolünden geçer. Bilinmeyen çağrı ve desteklenmeyen bildirim 503
döner; sessizce kabul edilmez.

`POST /api/teams/meetings/{meetingId}/join`, ayrı bir
`X-Teams-Control-Key` ile korunur. İstek kanonik toplantı UUID'sini, opak takvim
olayı referansını ve Teams katılım kimliklerini birlikte verir. Aynı takvim
referansı başka bir toplantıya veya başka katılım kimliklerine yeniden
bağlanamaz. Worker bu eşleştirmeyi Graph çağrısından önce kalıcı yazar.
`GET /api/teams/calls/{callId}` aynı anahtarla güncel çağrı durumunu ve kanonik
toplantı UUID'sini döndürür.

Katılım komutu aynı toplantı/olay için eşzamanlı veya yeniden başlatma sonrasında
tekrar gönderildiğinde ikinci Graph çağrısı oluşturulmaz. İstek gönderilmeden
önce rezervasyon kalıcı yazılır; başarılı yanıtın callId ve toplantı bağı birlikte
kaydedilir. Token alma gibi **Graph katılım isteği gönderilmeden** oluşan hata
ve kesin 400/401/403/404/429 ret yanıtı güvenli yeniden denemeye izin verir.
Gönderim sonrası zaman aşımı, 5xx veya eksik başarı yanıtı `join_outcome_unconfirmed`
olarak korunur; otomatik ikinci bot oluşturulmaz. Kalıcı yazma başarısızken
callId biliniyorsa worker yalnız o bot çağrısından çıkmayı dener. Çıkışın da
doğrulanamaması ayrı hata kodudur.

`GET /api/teams/meetings/{meetingId}/join-status`, belirsiz katılım ve bilinen
callId için özel kontrol uç noktasıdır. Operatör belirsiz durumda yeni toplantı
UUID'siyle deneme yapmamalı; mevcut Graph çağrısı/katılımcı listesi ve kayıt
zamanıyla incelemelidir. Bu sürüm belirsiz rezervasyonu otomatik silmez veya
yeniden başlatma ile temizlemez. Eşleştirmeyi kanıtlamadan dosya değiştirilmez.

Arka plan bakım işlemi aktif çağrılar için 15 dakikada bir `keepAlive` gönderir;
doğrulanamayan sonuç bir dakika sonra yeniden denenir. 404 çağrıyı sonlanmış
olarak işaretler. `POST /api/teams/calls/{callId}/leave`, yalnız bu worker'ın
bildiği çağrıdan botu çıkarır; toplantının kendisini sonlandırmaz. 204/404 dışında
çıkış başarılı sayılmaz. Bu işlemler de `X-Teams-Control-Key` ister.

Çağrı ve takvim eşleştirmeleri en fazla 1000 kayıtla sınırlandırılır.
`TeamsCapture__CallStateFilePath` ve `TeamsCapture__CalendarStateFilePath`
değerlerinin sonuna `.sqlite3` eklenerek iki yerel SQLite dosyası kullanılır.
Yazma işlemleri `journal_mode=DELETE`, `synchronous=EXTRA` ile commit edilir;
katılım rezervasyonunun commit'i tamamlanmadan Microsoft'a istek gönderilmez.
EXTRA, veriye ek olarak silinen işlem günlüğünün dizinini de senkronlar.
Depolama sistemi disk senkronlama ve dosya kilitlemeyi doğru desteklemelidir.
Dağıtım, önceden oluşturulmuş kalıcı dizin üzerinde tek replika olmalıdır;
worker eksik üst dizini kendisi oluşturmaz. Çok replika için ortak veritabanı
ve eşzamanlılık tasarımı ayrıca gerekir.

Eski JSON dosyası yalnız SQLite dosyası henüz yokken okunur ve ilk başarılı
yazmada SQLite'a taşınır; eski dosya değiştirilmez. SQLite mevcutsa bozuk/eksik
kayıt durumunda eski JSON'a dönülmez, işlem kapalı kalır. Depoyu sıfırlamak veya
SQLite'ı bilmeyen eski imaja dönmek ikinci bot riski oluşturur; böyle bir geri
alma ancak uzaktaki çağrılarla kayıtlar uzlaştırılarak yapılabilir.
Dosya yazma hata testleri ve süreç yeniden başlatma testleri fiziksel elektrik
kesintisi kabulü değildir. Kaynak: https://www.sqlite.org/pragma.html#pragma_synchronous

`TeamsCapture__CompletedCallRetentionHours` isteğe bağlıdır (1–2160 saat).
Varsayılan otomatik silme kapalıdır; kurum metadata saklama süresini belirler.
Açıldığında yalnız bütün çağrıları sonlanmış ve süreyi aşmış toplantının çağrı,
katılım rezervasyonu ve takvim eşleştirmesi temizlenir. Aktif/belirsiz kayıtlar
silinmez. Temizlik hatası aktif çağrıların bakımını durdurmaz. Süreyi aşan
silinmiş kayıtlarda tekrar isteğini engelleyen kayıt da kalmaz: üstteki toplantı
servisi bitmiş toplantıya yeni katılım komutu göndermemelidir. 1000 kayıt sınırı
doluysa operatör incelemesi gerekir; daha fazla Graph çağrısı oluşturulmaz.

Özel `GET /api/teams/readiness`, yapılandırma kontrolünü canlı ürün kabulünden
ayırır. `liveAudio`, `liveSpeakerAttribution`, `teamsSidePanel` ve
`automaticCalendarScan` bu sürümde **false** değerindedir. `/health` yanıtının
200 olması yalnız sürecin ayakta olduğunu gösterir. ClientSecret dahil gerekli
ayarlar olmadan katılım hazırlanmış sayılmaz; bu kontrol izin/credential
geçerliliğini veya callback'in Microsoft'tan erişilebilirliğini kanıtlamaz.

Mevcut tenant onayı Calendars izni içermediğinden seçilen etkinlik zamanlayıcısı
varsayılan kapalıdır. Elle `/join` akışında takvim bilgisi yetkili platform
servisinden gelir ve ek takvim izni gerekmez. Zamanlayıcı açılacaksa belgelenen
dar takvim okuma ve onlineMeeting izinleri ayrıca doğrulanır. Gerçek
secret sağlama, kalıcı volume, callback alan adı ve Teams manifest aktivasyonu
GitOps3716 üzerinden yürütülür.

Kullanıcının seçtiği takvim kaydı için kalıcı platform aktörü ve katılım anında
güncel yetki doğrulaması zorunludur. `TeamsScheduleAuthorization` ayarları ve
ayrı worker servis kimliği yokken yeni seçim kabul edilmez. Yetki reddi Graph'a
katılım isteği göndermez; doğrulama servisi ulaşılamıyorsa zaman penceresinde
yeniden denenir. Eski aktörsüz bekleyen kayıtlar otomatik katılmaz. Ayarlar,
güvenli sürüm sırası ve sınırlar: [Takvim yetkilendirmesi](../meeting-service/docs/teams-calendar.md#dispatch-time-authorization).

## Katılımcı ve ses kaynağı bilgisi

`GET /api/teams/calls/{callId}/participants`, kurum içi `X-Teams-Control-Key`
ile korunur. Yalnız bu worker'da kanonik toplantıya bağlı ve `established`
durumunda olan çağrının Microsoft Graph katılımcı listesini okur. Çağrı kimliği
bir toplantı UUID'si veya takvim olayı kimliği değildir.

Yanıt `callId`, `meetingId`, `observedAt` ve `participants` içerir. Her katılımcıda
`participantId`, varsa Teams `userId` / `displayName`, `isInLobby`, `isMuted` ve
yalnız `audio` medya kanallarının `audioSourceIds` değerleri bulunur. Eksik
isim/kimlik null kalır; telefon veya uygulama kimliği insan adı diye çevrilmez.
Bu alanlar Teams'in hesap/görünen ad bilgisidir, gerçek kişinin doğrulandığı
iddiası değildir. Yanıt `Cache-Control: no-store` taşır; liste kalıcı dosyaya
yazılmaz ve isim/ses kaynağı içerikleri loglanmaz.

`attributionStatus=roster-only-live-media-not-connected` her yanıtta açıktır:
bu liste **kimin şu anda konuştuğunu veya bir cümlenin sahibini söylemez**.
Bir medya adaptörü, ses alınırken aynı çağrının güncel kaynak bilgisini ve
zamanını korumalı; eski roster'ı geçmiş transkripte sonradan isim yapıştırmak
için kullanmamalıdır. `isMuted=false` konuşuyor demek değildir. Aynı mikrofonun
önündeki birden fazla insan yalnız Teams hesabıyla ayrıştırılamaz.

Koruma: kapalı yapılandırma 503; hatalı/eksik kontrol anahtarı 401; bilinmeyen
çağrı 404; kurulmamış/sonlanmış çağrı 409; Graph başarısızlığı veya kullanılamaz
liste 502. 15 saniye süre ve 1 MiB yanıt sınırı vardır. Eksik/paged liste,
tekrarlanan katılımcı kimliği ve farklı katılımcılarda aynı ses kaynağı
reddedilir; `@odata.nextLink` izlenmez, eksik liste tam liste diye sunulmaz.
Ses kaynağı numaraları çağrılar arasında birleştirilmez. Bu uç nokta tarayıcıya
ve Teams yan paneline kontrol anahtarı verme yetkisi değildir; kullanıcıya
sunulacak görünüm kanonik backend toplantı erişim kontrolünden geçmelidir.

Microsoft Graph, katılımcı okuma izninin çağrı oluşturulurken kontrol edildiğini
belirtir. Bu geliştirme yeni Graph izni istemez veya yönetici onayı vermez;
mevcut TEST uygulamasıyla gerçek çağrı kabulü ayrıca doğrulanmalıdır. Ham medya
seçeneği, ayrıca `Calls.AccessMedia.All` gibi medya izinlerinin ve kayıt durumu
protokolünün uygun kurulmasını gerektirir.

Kaynaklar:
- https://learn.microsoft.com/en-us/graph/api/call-list-participants
- https://learn.microsoft.com/en-us/graph/api/resources/mediastream
- https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/real-time-media-concepts
- https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/requirements-considerations-application-hosted-media-bots

Kurulum ve gerçek kabul sırası: [Canlı Teams ses bağlantısı](docs/live-teams-setup.md).

Callback sözleşmesi: https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/articles/calls/calling-notifications.html

## Teams manifest şablonu

`teams-app-manifest/manifest.template.json` sürüm 1.19 şemasını kullanır.
Paket kökünde manifest.json, color.png (192×192) ve outline.png (32×32,
beyaz/şeffaf) bulunmalıdır. İkonlar özgün mikrofon işaretidir; yeniden üretim:
`node teams-capture-worker/teams-app-manifest/generate-icons.mjs`.
Yerel/CI biçim kontrolü:
`node --test teams-capture-worker/teams-app-manifest/*.test.mjs`.
Bu kontrol tüm Microsoft şemasının veya tenant yüklemesinin kabulü değildir.

Şablonun app/bot ID, callback domain, website, privacy ve terms alanları
onaylı değerlerle doldurulmadan yüklenebilir paket olarak kullanılmaz.
Şablon bilinçli olarak gerçek secret içermez. Son manifest ayrıca Microsoft
1.19 şeması ve Teams yükleme doğrulamasından geçirilmelidir.
Kaynak: https://developer.microsoft.com/json-schemas/teams/v1.19/MicrosoftTeams.schema.json
Takip: GitOps #3716. Bu değişiklik tenant, DNS, izin veya runtime değiştirmez.

Onaylı beş açık yapılandırma alanından kurulacak paket dosyalarını üretme ve
DEV kontrolü: [İşletim ve teslim kılavuzu](docs/operations.md).

CI ayrıca varsayılan kapalı, root olmayan Linux container'ını çalıştırıp yeniden
başlatır ve SHA256 kayıtlı Docker image arşivini teslim eder. Tenant hazırlığı
sonrasında salt okunur `scripts/preflight.mjs`, beklenen uygulama/tenant kimliği
ile callback/control erişimini kontrol eder. Bu hazırlık paketi canlı medya,
Teams yan paneli veya gerçek toplantı kabulü olarak sunulmaz.

Main koşularında doğrulanan aynı arşiv GHCR'ye yayımlanır, registry manifestinin
test edilen imageID'yi taşıdığı kontrol edilir ve kaynak attestasyonu eklenir.
PR koşuları yayın yetkisi almaz. Registry digest ve kaynak commit'i ayrı artifact
olarak teslim edilir; TEST kurulumu canonical GitOps değişikliği gerektirir.

Çağrı yaşam döngüsü kaynakları:
- https://learn.microsoft.com/en-us/graph/api/call-keepalive?view=graph-rest-1.0
- https://learn.microsoft.com/en-us/graph/api/call-delete?view=graph-rest-1.0
