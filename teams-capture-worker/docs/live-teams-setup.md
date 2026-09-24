# Teams canlı ses ve konuşmacı bağlantısı — 22 Eylül 2026

## Doğrulanmış başlangıç

- 22 Eylül kişisel SSH kontrolü: `devai.acik.com` / `10.9.10.53`, Ubuntu
  22.04.5 LTS; `/home/zeynep/repos` ve kişisel GitHub erişimi çalışıyor.
  DEV geliştirme/özel loopback önizlemesi için kullanılabilir. Microsoft'un
  application-hosted ham medya SDK'sı için Windows Server yerine geçmez.
  Kullanıcının kısıtı yeni sunucu/hizmet satın almamaktır; önce mevcut kaynak
  uygunluğu belirlenir. Ortak preview ve TEST runtime bu çalışmayla değiştirilmez.

- 23 Eylül GitOps #3716 operatör kaydına göre Zeynep'in kurumsal hesabı TEST
  organizatörleri grubuna eklenmiştir. Kabul toplantısını kendi hesabıyla açar;
  `ai@acik.com` servis kutusuyla etkileşimli oturum açılmaz. Organizatörün kullanıcı
  kimliği ile botun Entra uygulama kimliği farklıdır. EXO policy `Granted`
  sonucu, `Calls.JoinGroupCall.All` iznini bu kullanıcılarla sınırlayan veya botun
  toplantıya gerçekten katıldığını gösteren bir kanıt değildir.
- GitOps #3716 kaydındaki Entra uygulaması `meeting-intelligence-teams-bot-test`,
  uygulama kimliği `cb8dc8da-0168-470d-9d35-908fdfcc3fa3`. Kayıt yalnız
  `Calls.JoinGroupCall.All` onayını bildirir; bu bugünkü tenant sorgusu değildir.
- Backend PR1171 `codex/teams-calendar-capture` dalına birleştirilmiştir.
  `main` dalında 22 Eylül okumasında `teams-capture-worker` yoktur.
  PR1173, 24 Eylül'de çakışması giderilmiş ve testleri geçerek incelemeye açılmıştır.
  Kaynak hazırlığı, deployment ve tenant kabulü anlamına gelmez.
- Kaynak `serviceHostedMediaConfig` ile katılır; ses soketi açmaz.
- Backend TEST hedefi `aiserver` / `10.9.10.15`, `k3d-test` / `platform-test`.
  Meeting AI ayrı Denetim GPU bilgisayarında, köprü `10.99.0.2:8300`.
  Bu iki host kaydı Windows medya botu sunucusunun hazır olduğunun kanıtı değildir.

## Yöntem seçimi

Mevcut worker'a bir ayar eklemek ham ses sağlamaz. Microsoft kendi medya
platformunu genel AI toplantı ajanları için önermiyor. Aşağıdaki alternatifler
görünür tutulur; hiçbiri yalnız bu dokümanla seçilmiş/etkinleşmiş sayılmaz.

1. **Microsoft application-hosted media:** mevcut kurumsal Entra kimliğiyle
   ayrı medya bileşeni. Microsoft belgesine göre TEST/geliştirme için kurum içi
   Windows Server kullanılabilir; üretim için Azure'da Windows Server gerekir.
   Linux `aiserver` aynı işlevi üstlenemez. C# medya SDK'sı, internetten erişilen
   HTTPS callback, uygun sertifika, medya public IP/port erişimi gerekir.
   Mevcut service-hosted çağrı sonradan medya okuyucuya dönüşmez; medya oturumunu
   açan bileşenin aynı çağrıyı oluşturması/katılması gerekir. Paralel ikinci bot
   veya iki ayrı callId birbirinin ses/katılımcı kimliği gibi kullanılmaz.
2. **Aracı toplantı hizmeti:** Teams sesini ve katılımcı bilgisini sağlayan
   hizmet adaptörü. Veri akışı, kurum seçimi, maliyet ve sağlayıcı testi ayrı
   değerlendirilir. Hesap/satın alma veya toplantı sesi aktarımı yapılmadı.
3. **Microsoft hazır ajan/transkript yaklaşımı:** alternatif olarak
   değerlendirilebilir. Toplantı sonrası transkript almak kullanıcının canlı
   karar/aksiyon gereksinimini karşılamaz; canlı kapsam ayrıca kanıtlanır.

## Microsoft yolu için IT ile doğrulanacak somut bilgiler

Parola veya özel anahtar sohbet/mail/repo içine yazılmaz. Gerekli alanlar:

- Test organizatör hesabının Teams toplantısı ve Outlook takvim/mailbox durumu.
- Mevcut Entra uygulamasının `Tenant ID` ve `Application (client) ID` teyidi;
  tekrar uygulama açmadan mevcut kayıt kullanılır.
- Kurumun izin verdiği TEST Windows Server adı ve erişim yöntemi; Azure
  kullanılacaksa ilgili abonelik/kaynak grubu ve yetkili operatör.
- Azure Bot / Teams calling channel kaydı, onaylı callback alan adı, sertifika
  ve gerekli dış ağ erişiminin kim tarafından sağlanacağı.
- Kurum secret yönetimindeki uygulama credential kaydı ve medya sertifikası
  kayıt adı. Değerleri çalışma dosyasına veya kaynak koda kopyalanmaz.
- `Calls.JoinGroupCall.All` mevcut onayı ve Microsoft medya yolunda gerekli
  `Calls.AccessMedia.All` yönetici onayı. Takvim erişimi ayrı ve dar kapsamlıdır;
  katılım izni otomatik takvim taraması değildir.
- Teams uygulama paketinin TEST tenant'a yüklenebilmesi ve toplantı politikası
  / lobi davranışı. EXO erişim politikası tek başına botun bütün Teams çağrı
  erişimini sınırlıyor kabul edilmez.

## Uygulama sırası ve kabul

**Hesap dışındaki açık işler:** seçilen canlı medya adaptörünün uygulanması,
kanonik ses/STT/analiz hattına bağlanması, toplantıya yetkili Teams yan paneli
ve organizatör takviminden zamanlı katılım akışının bağlanması henüz bitmiş
değildir. Roster ve kontrol düzlemi testlerinin geçmesi bu işleri tamamlamaz.
Kuruma hesap talebi yapmak bu işlerin tek engelinin hesap olduğu anlamına gelmez.

1. Mevcut katılım/kontrol paketi ve gerçek tenant ayarları doğrulanır. Callback
   kimlik kontrolü, kalıcı çağrı-toplantı bağı, restart ve terminal çağrı davranışı
   test edilir. Uç noktanın ayakta olması Teams'e katıldı kanıtı değildir.
2. Seçilen medya bileşeni kendi medya oturumuyla toplantıya katılır. Gerekli
   katılımcı bilgilendirmesi/onay ve Microsoft kayıt durumu akışı başarılı olmadan
   ses işleme/kayıt açılmaz. Ham ses saklama varsayılan kapalıdır.
3. Katılımcı bilgisi `participantId` ve `mediaStreams[].sourceId` ile okunur.
   Gelen sesin kaynak kimliği, aynı callId/oturum ve ses zamanı içinde eşleştirilir.
   REST roster anlık bilgidir: yeniden katılma/kaynak numarası değişimi için
   SDK katılımcı değişiklikleri izlenir; eski bir roster gelecekteki/geçmişteki
   bütün cümlelere uygulanmaz.
4. Ses akışı mevcut STT/analiz hattına bağlanır. İki kişinin üst üste konuşması
   veya kaynak bilgisinin eksikliği halinde rastgele tek isim seçilmez. Katılımcı
   hesabı ve doğrulanmış gerçek kişi farklı kavramlardır. Ortak mikrofon ayrı
   akustik ayrım ve isim doğrulama gerektirir.
5. Yetkili Teams yan paneli, backend toplantı erişim kontrolünden geçerek tüm
   konuşmacıların metnini ve toplantı sürerken analizini gösterir. Mevcut bot-only
   manifest yan panel içermez; panel kurulumu ayrı iş kalemidir. İç kontrol
   anahtarı web/mobile/Teams istemcisine verilmez.
6. İki ayrı hesapla gerçek TEST toplantısında sıra alma, üst üste konuşma,
   sessize alma, ayrılma/yeniden katılma ve aynı görünen ad senaryoları denenir.
   Konuşmacı adı, cümle, zaman ve kanonik toplantı birlikte doğrulanır.
   Kayıt durdurulmadan karar/aksiyonun panelde görünmesi ayrı kabul şartıdır.

Yerel sentetik testler Graph/Teams tenant, gerçek ses, gerçek isim eşleşmesi veya
son kullanıcı yan panel kabulü değildir. Bu aşamada issue kapatılmaz.

## Kaynaklar

- https://github.com/Halildeu/platform-k8s-gitops/issues/3716
- https://github.com/Halildeu/platform-backend/pull/1171
- https://github.com/Halildeu/platform-backend/pull/1173
- https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/requirements-considerations-application-hosted-media-bots
- https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/real-time-media-concepts
- https://learn.microsoft.com/en-us/microsoftteams/platform/bots/calls-and-meetings/registering-calling-bot
- https://learn.microsoft.com/en-us/graph/api/call-list-participants
- https://learn.microsoft.com/en-us/graph/api/resources/mediastream
