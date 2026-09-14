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

Bu bileşen tek başına bot katılımını sağlamaz. Program sağlık ve kimlik
doğrulamalı `/api/teams/callback` uç noktalarını açar. Callback Microsoft'un
Skype OpenID anahtarları, botframework issuer, uygulama audience, süre ve
tenant kontrolünden geçer. Bilinmeyen çağrı ve desteklenmeyen bildirim 503
döner; sessizce kabul edilmez. Çağrı durumları en fazla1000 kayıtla süreç
belleğinde tutulur; restart sonrası devamlılık veya çok replika desteği yoktur.
Bu sınırda test worker'ı hazır kabul edilmemelidir. Production öncesi kalıcı
çağrı eşleştirme ve geri alma yolu gereklidir.
Authenticated join endpoint ve izinli takvim çözümleyici tamamlanmadan servis hazır kabul edilmez.
Mevcut tenant onayı Calendars izni içermediğinden otomatik takvim sorgusu
eklenmemiştir. Gerçek secret sağlama, callback alan adı ve Teams manifest
aktivasyonu GitOps3716 üzerinden yürütülür.

Callback sözleşmesi: https://microsoftgraph.github.io/microsoft-graph-comms-samples/docs/articles/calls/calling-notifications.html
