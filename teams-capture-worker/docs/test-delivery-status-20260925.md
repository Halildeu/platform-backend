# Teams bot — TEST teslim durumu ve kurumdan beklenenler

25 Eylül 2026 durum kaydı. Halil Bey / IT incelemesi içindir.

**Katılım, takvim ve yan panel kaynakları ana sürüme alındı; TEST kurulumu ve
gerçek Teams kabulü tamamlanmadı. Canlı ses adaptörü henüz uygulanmadı. Bu nedenle
“yalnız hesap kaldı” aşamasında değiliz.**

## Hazırlanan kaynak ve paketler

| İş | Kanıt | Sınır |
| --- | --- | --- |
| Katılım, callback doğrulama, çağrı durumu, yeniden başlatma ve çıkış | [backend #1187](https://github.com/Halildeu/platform-backend/pull/1187), [paket yayını #1189](https://github.com/Halildeu/platform-backend/pull/1189) | Kontrol bileşeni; Teams sesini almaz. |
| Kullanıcının seçtiği Outlook etkinliğine zamanlı katılım | [backend #1191](https://github.com/Halildeu/platform-backend/pull/1191) | Varsayılan kapalı; gerçek takvim izinleri ve TEST kabulü açık. |
| Organizatör kimliği, yetkili takvim listesi, seçim, durum ve iptal | [backend #1193](https://github.com/Halildeu/platform-backend/pull/1193), [#1194](https://github.com/Halildeu/platform-backend/pull/1194), [#1195](https://github.com/Halildeu/platform-backend/pull/1195) | Kullanıcı şifresi veya iç kontrol anahtarı tarayıcıya verilmez. |
| Katılmadan hemen önce güncel kimlik ve toplantı yetkisi denetimi | [backend #1196](https://github.com/Halildeu/platform-backend/pull/1196) | Kaynak `212fac600f7ca930319241c258a9d216d0068ffd`; TEST'e kurulmadı. |
| Yetkili Teams yan paneli ve Outlook seçim ekranı | [web #1197](https://github.com/Halildeu/platform-web/pull/1197), [#1198](https://github.com/Halildeu/platform-web/pull/1198) | Kaynak `e16e0784776dcfc74316187d35245e47e4216250`; gerçek Teams oturumu ve kurulum açık. |

Güncel worker paketi:

```text
ghcr.io/halildeu/platform-backend-teams-capture-worker@sha256:deb81e26b03ccb68e2f79aba5d8d493de7322ba566d97f33fc580ff5f6d69e12
```

[Worker main koşusu 36101660163](https://github.com/Halildeu/platform-backend/actions/runs/36101660163),
[servis imaj yayını 36101660161](https://github.com/Halildeu/platform-backend/actions/runs/36101660161)
ve [panel paketi 36099769386](https://github.com/Halildeu/platform-web/actions/runs/36099769386)
başarılıdır. Worker ve meeting-service kaynak attestasyonları ayrıca doğrulandı.
Auth-service yayını başarılı, ancak bağımsız yerel imza doğrulaması tamamlanmadı:
paket sorgusu mevcut GitHub oturumunda eksik `read:packages` kapsamı nedeniyle
403 döndü. Bu durum CI'da yayımlanan imajın bulunmadığını kanıtlamaz.

#1196 için 117 Java, 199 .NET ve 46 Node testi; ayrı kaynak incelemesinde ayrıca
45 .NET testi geçti. PR kontrollerinde 26 başarı, beklenen bir yayın atlaması ve
değişmemiş ana sürümde de doğrulanmış bir MinIO indirme yetki hatası vardır.
“Tüm CI başarılı” veya “gerçek Teams testi geçti” denmez.

## Halil Bey / IT tarafından netleştirilmesi gerekenler

1. **Azure Bot:** Kullanılacak kurum aboneliği/kaynak grubu ve kurulumu yapacak
   operatör. Mevcut Entra uygulamasıyla Azure Bot kaydı ve Teams calling ayarı
   tamamlanmalı; ikinci, farklı bir bot kimliği varsayılmamalı.
2. **Canlı ses yöntemi ve mevcut sunucu:** Hangi mevcut sunucu/yöntemin kurumca
   uygun görüldüğü, erişim ve ağ/sertifika sorumlusu. Yeni sunucu veya ücretli
   hizmet satın alma kararı alınmış değildir. Seçenekler ve teknik sınırlar
   [canlı Teams kurulumu](live-teams-setup.md#yöntem-seçimi) belgesindedir.
3. **Microsoft izinleri:** Mevcut kayıt yalnız `Calls.JoinGroupCall.All` onayını
   bildiriyor. Seçili takvim akışı için [takvim izinleri ve erişim politikaları](calendar-scheduling.md#ön-koşullar-ve-kapalı-başlangıç)
   ayrıca doğrulanmalı; organizatör grubundaki `Granted` sonucu bütün Teams,
   takvim ve medya izinleri tamamlandı anlamına gelmez. Canlı medya izinleri
   seçilen yönteme göre ayrıca onaylanmalı.
4. **Güvenli ayarlar:** Bot uygulama credential'ı, iç kontrol anahtarı ve ayrı
   worker servis credential'ı TEST secret store üzerinden sağlanmalı. Tenant,
   client ve organizatör kimlikleri teyit edilmeli. Şifre/secret PR'a, e-postaya
   veya kaynak koda yazılmamalı; yalnız kayıt adı ve hazır olma durumu paylaşılmalı.
5. **Kurulum koordinasyonu:** GitOps Project #2 mevcut kişisel oturumda
   `ProjectV2 bulunamadı` döndürüyor. [gitops #3716](https://github.com/Halildeu/platform-k8s-gitops/issues/3716)
   hâlâ Backlog ve üstlenilmemiş durumda. Pano erişimi ve kurulum işinin
   başlatılabilir duruma alınması gerekiyor; bu PR iş üstlenme veya kurulum
   onayı yerine geçmez.
6. **Teams uygulaması:** Onaylı uygulama paketi, yan panel adresi ve oturum açma
   ayarlarının TEST tenant'a yüklenebilmesi. Kurulumda ilgili servis imajlarını
   çekme erişimi de doğrulanmalı.

Zeynep'in hesabı TEST toplantısını açacak **organizatördür**. Bot, ayrı Entra
uygulama kimliğiyle çalışır. Organizatörün gruba eklendiği
[23 Eylül operatör kaydında](https://github.com/Halildeu/platform-k8s-gitops/issues/3716#issuecomment-5791279505)
bildirilmiştir. 25 Eylül'de kullanıcı, abonelik ve medya sunucusu hakkında yeni
IT yanıtı gelmediğini teyit etti.

## Bizde kalan geliştirme ve kurulum

- Uyumlu backend servislerini, worker'ı ve paneli GitOps üzerinden TEST'e
  kurmak. Worker için kalıcı alan, tek replika, özel kontrol erişimi ve güvenli
  ayarlar gerekir. [Katılım anı yetkilendirme sözleşmesi](../../meeting-service/docs/teams-calendar.md#dispatch-time-authorization)
  ve buradaki sürüm sırası korunmalı.
- Gerçek callback adresini her yerde aynı tanımlamak:
  `https://testai.acik.com/api/teams/callback`. Dışarıya worker'ın kontrol
  yolları açılmamalı. Gerçek Microsoft bildirimleriyle doğrulama yapılmalı.
- Onaylanan yönteme göre Teams canlı ses adaptörünü geliştirmek. Mevcut
  `serviceHostedMediaConfig` katılımı ham sesi sağlamaz.
- Aynı çağrı ve ses zamanı içinde ses kaynağını Teams katılımcısıyla
  eşleştirmek; yeniden katılma ve kaynak değişimlerini izlemek. Belirsiz
  kaynağa kişi adı atanmamalı. Katılımcı listesini okumak tek başına bunu çözmez.
- Gelen sesi mevcut STT/analiz hattına bağlamak; yetkili panelde metin,
  **karar ve aksiyonları toplantı sürerken** göstermek.
- İki kişiyle zamanlı katılım, gerçek callback, isimli metin, canlı analiz,
  bağlantı kesintisi/yeniden katılım, yetki reddi ve toplantıdan çıkışı doğrulamak.

## Mevcut çalışma ortamı kanıtının sınırı

25 Eylül anonim kontrollerinde callback'e GET ve imzasız boş POST 401 JSON
döndü. Bu yalnız anonim isteğin reddini gösterir; isteğin doğru worker'a
ulaştığını veya Microsoft callback'inin kabul edildiğini göstermez.
`/teams/panel/config.json` adresi 200 HTML döndü; beklenen JSON yapılandırması
bu adreste doğrulanamadı.

Bu belge bir kurulum ya da canlı ürün kabulü değildir. Mobil, Electron, ortak
STT/noktalama ayarları ve GPU ortamı bu teslim kapsamında değiştirilmez.
