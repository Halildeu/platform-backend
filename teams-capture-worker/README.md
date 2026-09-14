# Teams Capture Worker

Bu worker, Teams/Calendar toplantısına katılan botun kontrol düzlemidir. Masaüstü
ve mobil uygulama koduna dokunmaz.

Akış: Teams/Calendar bot katılımı → kullanıcı onaylı mobil/masaüstü recorder →
audio-gateway session → Direct-STT → meeting-ai → meeting-service kanonik sonuç.
Özet, kararlar, aksiyonlar ve görev atamaları worker tarafından üretilmez veya
saklanmaz; yalnız kanonik sonuç okunur.

Bu worker canlı ses almaz. Microsoft Graph'in ham medya botu yalnız uyumluluk
kaydı senaryoları için uygundur; Meeting Intelligence'ın genel ürün yolu değildir.
Katılımcı bilgilendirmesi ve recorder rızası, bot katılımından bağımsızdır.

Gerekli Microsoft tarafı: tenant ve application kimlikleri, HTTPS callback,
`Calls.JoinGroupCall.All` için yönetici onayı ve Teams manifestinde
`supportsCalling=true`. Bu değerler kaynak koda yazılmaz.

## Kaynak uygulama durumu

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

Çağrı ve takvim eşleştirmeleri en fazla1000 kayıtla sınırlandırılır ve
`TeamsCapture__CallStateFilePath` ile `TeamsCapture__CalendarStateFilePath`
yollarına atomik olarak yazılır. Dağıtımda bu yollar kalıcı bir volume üzerinde
olmalı ve worker tek replika çalışmalıdır. Böylece süreç veya pod yeniden
başladığında çağrı takibi devam eder. Çok replika için sonraki aşamada ortak
veritabanı adaptörü gerekir.

Mevcut tenant onayı Calendars izni içermediğinden worker tenant takvimlerini
Graph üzerinden taramaz. Takvim bilgisi, toplantıyı zaten bilen yetkili platform
servisinden gelir; böylece yeni ve geniş bir Microsoft izni gerekmez. Gerçek
secret sağlama, kalıcı volume, callback alan adı ve Teams manifest aktivasyonu
GitOps3716 üzerinden yürütülür.

Callback sözleşmesi: https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/articles/calls/calling-notifications.html
