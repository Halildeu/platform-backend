# Teams Capture Worker

Bu worker, Teams toplantısına katılan botun medya kaynağını mevcut Faz 24
hattına bağlamak içindir. Masaüstü ve mobil uygulama koduna dokunmaz.

Akış: Teams kayıt başlangıcı onayı → audio-gateway session → Direct-STT →
meeting-ai → meeting-service kanonik sonuç. Özet, kararlar ve aksiyonlar
worker tarafından üretilmez veya saklanmaz; yalnız kanonik sonuç okunur.

Bu ilk dilimde ses alma kapalıdır. Canlı medya açılmadan önce Microsoft Entra
uygulama kaydı, yönetici izinleri, Teams bot manifesti ve katılımcı
bilgilendirme/rıza yüzeyi tamamlanmalıdır.
