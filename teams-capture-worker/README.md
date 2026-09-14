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

Bu bileşen tek başına bot katılımını sağlamaz. Program halen yalnız sağlık
uç noktası açar. Authenticated join endpoint, izinli takvim çözümleyici ve
doğrulanmış callback işleyicisi tamamlanmadan servis hazır kabul edilmez.
Mevcut tenant onayı Calendars izni içermediğinden otomatik takvim sorgusu
eklenmemiştir. Gerçek secret sağlama, callback alan adı ve Teams manifest
aktivasyonu GitOps3716 üzerinden yürütülür.
