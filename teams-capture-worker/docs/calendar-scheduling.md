# Seçilen Outlook toplantısına zamanlı katılım

Bu özellik kurum içi kontrol istemcisinin **açıkça seçtiği** Outlook etkinliğini
izler. Posta kutusundaki bütün etkinlikleri taramaz. Takvim saati ve iptal bilgisi
Microsoft Graph'tan okunur; istemcinin gönderdiği tarih veya bağlantıyla katılmaz.
Mevcut görünür bot katılımını kullanır. Canlı ses, Teams yan panelinden seçim ve
iki kişili gerçek tenant kabulü ayrı açık işlerdir.

## Ön koşullar ve kapalı başlangıç

- Mevcut bot kimliği, callback, `Calls.JoinGroupCall.All` ve kontrol anahtarı.
- Seçilen organizatörün takvim etkinliği için `Calendars.ReadBasic` (API'nin
  belgelenen en dar uygulama okuma izni; gerekli alanların tenant denemesinde
  dönmesi doğrulanır). Mevcut daha geniş izin varsa ayrıca büyütülmez.
- Bağlantıyı katılım kimliklerine çevirmek için `OnlineMeetings.Read.All` ve
  **Teams online meeting application access policy**. EXO takvim erişim politikası
  bunun yerine geçmez. Microsoft izinleri ve mailbox kapsamı IT tarafından
  doğrulanmadan özellik açılmaz; bu kod izin onayı vermez.
- `TeamsCapture__CalendarSchedulingEnabled=true` (varsayılan false).
- `TeamsCapture__CalendarOrganizerIds__0=<onaylı organizatörün Entra kullanıcı UUID'si>`;
  gerektiğinde devam eden indeksler. E-posta ve bot client ID bu alana yazılmaz.
- `TeamsCapture__CalendarScheduleStateFilePath=/var/lib/teams-capture/schedules.json`.
  Asıl SQLite dosyası bu yol + `.sqlite3` olur. Çağrı/takvim dosyalarından ayrı,
  aynı dayanıklı volume içinde; tek replica ve önceden oluşturulmuş üst dizin.

Alanları yapılandırmak gerçek Graph/Teams iznini veya katılımı kanıtlamaz.
`/api/teams/readiness` içindeki `selectedCalendarSchedulingConfigured` yalnız
yerel ayar kontrolüdür; `automaticCalendarScan` false kalır.

## Kurum içi API

Kontrol anahtarı yalnız sunucudan `X-Teams-Control-Key` başlığıyla gönderilir.
Son kullanıcı/Teams iframe'ine verilmez. Çağıran backend, kullanıcının kanonik
toplantıya erişimini ve bu takvim seçimini yapma yetkisini ayrıca denetlemelidir.
Bu uçlar internete açılmaz; dışarıya yalnız Microsoft callback yolu açılır.

`POST /api/teams/meetings/{kanonik-toplantı-UUID}/calendar-schedule`:

```json
{
  "organizerId": "<onaylı kullanıcının UUID'si>",
  "eventId": "<Outlook etkinlik ID'si>",
  "correlationId": "<istek-takip-referansı>"
}
```

202, etkinlik okunup kuyruğa kalıcı yazıldı demektir; bot henüz katılmış sayılmaz.
Aynı seçim yeniden gönderilirse mevcut durum döner, başarısız/iptal edilmiş
iş yeniden kurulmaz. Aynı etkinlik başka kanonik toplantıya bağlanamaz. Aynı
kanonik toplantı başka seçime çevrilmez. En fazla 100 kayıt ve başlangıçta en
fazla 90 gün ilerisi kabul edilir; seriesMaster yerine belirli occurrence/
exception veya tekil toplantı seçilmelidir.

`GET .../calendar-schedule` durum verir. `DELETE .../calendar-schedule`, henüz
gönderime alınmamış işi iptal eder. Gönderime alınmış/katılmış işte 409 döner;
katılmış çağrının çıkışı mevcut `/api/teams/calls/{callId}/leave` üzerinden ayrı
yapılır. Takvim işini silmek, çağrıdan çıkılmış gibi sunulmaz.

## Zaman ve hata davranışı

- Bekleyen etkinlikler en fazla 30 saniyelik hedef aralıkla kontrol edilir;
  ana zamanlayıcı 5 saniyede bir çalışır. İstek gecikmesi/Graph sınırları bu
  hedefi uzatabilir; kesin saniye garantisi verilmez. Aynı anda en fazla 4 iş.
- Başlangıç gelince güncel etkinlik okunur, opak join URL Graph `onlineMeetings`
  sorgusuyla çözülür ve etkinlik bir kez daha okunur. Eski URL biçimi parçalanmaz.
- Başlangıçtan önce katılmaz. Başlangıçtan 5 dakikadan fazla geçmişse veya
  etkinlik bitmişse geç katılım yapmaz. Sunucu çok geç açılmışsa iş `expired` olur.
- İptal veya zaman değişimi gözlenirse eski bilgiyle katılım yapılmaz. Etkinlik
  değişikliği ile Microsoft çağrı oluşturma atomik değildir; son okuma sonrası
  eşzamanlı değişiklik veya katılım sonrası takvim iptali otomatik çıkış garantisi
  değildir. Çıkış ayrı kontrol akışıdır.
- 401/403/429, yönlendirme, bozuk/eksik/aşırı büyük yanıt ve geçersiz
  onlineMeeting kimliği katılım izni sayılmaz. Graph okuması başarısızsa
  bekleyen iş ertelenir; hiç okunamayan kayıt operatör iptali gerektirebilir.
  Seçilen etkinlik 404 dönerse `failed/calendar_event_not_found` olur; silinen
  veya ID'si taşınmayla değişen etkinlik sonsuza kadar kuyrukta beklemez.
- Katılım isteğinin hiç çağrı oluşturmadığı doğrulanırsa (ör. token alınamaması,
  Graph'ın kesin 429 reddi) rezervasyon bırakılır; en erken 30 saniye sonra güncel
  etkinlik ve katılım penceresi tekrar kontrol edilir. Belirsiz POST sonucu bu
  güvenli yeniden deneme kapsamına alınmaz.
- Gönderimden önce `dispatching` kalıcı yazılır. Yeniden başlatmada bu durum
  mevcut çağrı makbuzuyla eşleştirilir; belirsiz istek körlemesine tekrarlanmaz.
  `failed/interrupted_join_requires_reconciliation` operatör incelemesi ister.
- `joined` katılım isteğinin makbuzudur; toplantının şu an `established`
  olduğunu görmek için çağrı durumu/callback kontrol edilir.
- Tamamlanan seçim metadata'sı yalnız mevcut kurum retention parametresiyle
  temizlenir. Parametre yoksa otomatik silme yoktur; kapasite dolunca 409 döner.
  İsim, etkinlik gövdesi, katılımcı listesi ve join URL diske yazılmaz. Etkinlik
  kimliği, organizatör UUID'si ve zamanlar kalıcı metadata'dır; HTTP URI logger'ı
  join URL sızdırmaması için bu Graph istemcisinde kapalıdır.

Gerçek kabul: izinli TEST organizatörüyle gelecek saatli seçim, saat değişikliği,
iptal, uygulama restart'ı, tek bot katılımı ve kontrol anahtarı olmayan ret.
Yerel fake-Graph testleri bu tenant kabulünün yerine geçmez.

Microsoft sözleşmeleri:
- https://learn.microsoft.com/en-us/graph/api/event-get?view=graph-rest-1.0
- https://learn.microsoft.com/en-us/graph/api/onlinemeeting-get?view=graph-rest-1.0
