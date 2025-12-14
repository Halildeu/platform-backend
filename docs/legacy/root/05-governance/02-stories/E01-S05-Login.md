# Story E01-S05 – Shell Login & Register (Tailwind + i18n)

- Epic: E01 – Identity  
- Story Priority: 110  
- Tarih: 2025-12-06  
- Durum: Done

## Kısa Tanım

Shell uygulamasının giriş (login), kayıt (register) ve yetkisiz erişim (unauthorized) akışlarının Ant Design bağımlılıklarından arındırılıp Tailwind + UI Kit primitives’i ile yeniden yazılması; i18n mimarisinin `@mfe/i18n-dicts` paketindeki `common` namespace’i üzerinden dört dil (tr/en/de/es) için çalışır hale getirilmesi.

## İş Değeri

- Kimlik modülünün giriş akışları tüm MFE’ler için tek ve tutarlı bir Shell üzerinden yönetilir.
- Ant Design bağımlılıkları kaldırılarak UI Kit + Tailwind tabanlı sade, sürdürülebilir bir auth arayüzü elde edilir.
- Çok dilli kurumsal kullanım (TR/EN/DE/ES) için login/register/unauthorized metinleri tek sözlükten yönetilir; hard-coded metinler ortadan kalkar.
- Gelecekteki tema ve erişilebilirlik iyileştirmeleri auth ekranlarında merkezi olarak uygulanabilir (ADR-016 theme/layout sistemi ile uyumlu).

## Bağlantılar (Traceability Links)

- SPEC: `docs/05-governance/06-specs/SPEC-E01-S05-IDENTITY-AUTH-V1.md`  
- ACCEPTANCE: `docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`  
- ADR: ADR-003 (Auth & BroadcastChannel), ADR-008 (i18n & Dictionary Packaging), ADR-016 (Theme & Layout System)  
- STYLE GUIDE: `docs/00-handbook/NAMING.md`

## Kapsam

### In Scope
- `mfe-shell` içindeki:
  - Header dil seçici ve tema anahtarı ile ilişkilendirilen login/register/unauthorized metinleri.
  - Login sayfası (`LoginPage.ui.tsx`) — form, hata mesajları, yönlendirmeler.
  - Register sayfası (`RegisterPage.ui.tsx`) — form alanları, validasyon ve başarı/başarısızlık akışları.
  - Unauthorized sayfası (`UnauthorizedPage.ui.tsx`) — mesajlar ve geri dönüş navigasyonu.
  - Login popover bileşeni (`LoginPopover.ui.tsx`) — Shell header içindeki hızlı giriş deneyimi.
- Ant Design bileşenlerinden arınma:
  - Login/register/unauthorized ve ilgili Shell header parçalarında `Form`, `Input`, `Button`, `Card`, `Typography`, `Tooltip`, `Badge` vb. Ant bileşenlerinin kaldırılması.
  - UI Kit `Button` ve Tailwind tabanlı layout/form primitives’inin kullanılması.
- i18n:
  - Tüm auth metinlerinin `@mfe/i18n-dicts` altındaki `common` namespace’ine taşınması (`auth.login.*`, `auth.register.*`, `auth.popover.*`, `auth.unauthorized.*`, `auth.session.*`, `shell.header.*`, `shell.nav.*`).
  - Shell’de `useShellCommonI18n` hook’u ile `common` sözlüğünün preload edilmesi ve dil değişiminde header + login/register/unauthorized ekranlarının yeniden render edilmesi.

### Out of Scope
- Backend kimlik doğrulama protokol değişiklikleri (JWT yapısı, key rotation, SSO entegrasyonları).
- Keycloak realm/clients konfigürasyonu ve rollerin iş kuralları (bunlar ilgili security/identity dokümanlarından yönetilir).
- Access/Reporting/Audit MFE’lerinin iç ekranlarındaki i18n ve Tailwind migrasyonlarının tamamı (bunlar ayrı Story’lerde ele alınacaktır).

## Task Flow (Ready → InProgress → Review → Done)

```text
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Task                                                        | Ready        | InProgress    | Review       | Done        |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
| Login formunun Tailwind + UI Kit ile yeniden yazılması      | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Register formunun Tailwind + UI Kit ile yeniden yazılması   | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Unauthorized ve LoginPopover akışlarının yeniden tasarlanması| 2025-12-05  | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Auth metinlerinin ortak i18n sözlüğüne taşınması            | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Dil seçici entegrasyonu ve çok dilliliğin test edilmesi     | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
| Auth akışları için unit + integration testlerinin yazılması | 2025-12-05   | 2025-12-05    | 2025-12-06   | 2025-12-06  |
+-------------------------------------------------------------+--------------+---------------+--------------+-------------+
```

**Kural:**
- İlk tarih “Ready” → görev hazır  
- İkinci tarih “InProgress” → çalışma başladı  
- Üçüncü tarih “Review” → kod kontrol aşamasında  
- Son tarih “Done” → tamamen tamamlandı  
- Soldaki sütun dolmadan sağdaki sütun doldurulmaz (Ready → InProgress → Review → Done akışına uyulur).

## Fonksiyonel Gereksinimler

1. Kullanıcı, geçerli kimlik bilgileri ile login formunu gönderdiğinde Shell, mevcut auth servis API’si üzerinden oturum açar ve başarılı durumda ana sayfaya yönlendirir.
2. Hatalı kimlik bilgilerinde kullanıcıya i18n ile çevrilmiş, hassas bilgi içermeyen hata mesajı gösterilir.
3. Kayıt (register) formunda zorunlu alanlar (örn. e-posta, parola, parola doğrulama) Tailwind tabanlı validasyon ve helper metinleri ile yönetilir; API’den dönen iş kuralı hataları kullanıcıya çevrilmiş mesajlarla gösterilir.
4. Yetkisiz erişim (unauthorized) ekranı, kullanıcının erişmeye çalıştığı sayfayı özetleyen metin + Shell’e geri dönüş veya login ekranına yönlendirme seçenekleri sunar.
5. Dil seçici TR/EN/DE/ES arasında geçiş yaptığında header, login, register, unauthorized ve login popover metinleri seçilen dile göre anında güncellenir (full page refresh olmadan).
6. Default dil TR, fallback dil EN olarak yapılandırılır; DE/ES sözlükleri en azından EN fallback’i ile aynı anahtar setini sunar (boş/eksik key kalmaz).

## Non-Functional Requirements

- Performance:
  - Login/register sayfaları için ilk yükleme süresi, tema ve i18n preload işlemleriyle anlamlı şekilde artmamalıdır.
  - Sözlük preload işlemleri, auth ekranında kullanıcı etkileşimini bloklamadan gerçekleşmelidir.
- Security:
  - Hata mesajları kullanıcıya kimlik doğrulama altyapısına ilişkin hassas detay (hangi alanın yanlış, var olmayan kullanıcı vs.) sızdırmamalıdır.
  - Form alanları XSS ve CSRF korumaları ile uyumlu kalmalıdır (mevcut backend güvenlik katmanı ile çelişmemeli).
- UX:
  - Tüm interactif alanlar (input, button, link) için klavye ile gezinme (Tab/Shift+Tab) sorunsuz çalışmalıdır.
  - i18n ile gelen metinler üç satırı aşmayacak şekilde okunaklı kalmalı; hata/help metinleri için yeterli kontrast sağlanmalıdır.

## İş Kuralları / Senaryolar

- “Geçersiz kimlik bilgisi” → Kullanıcıya genel bir hata mesajı gösterilir, deneme sayısı kısıtlamaları (varsa) backend tarafından yönetilir.
- “Yeni kullanıcı kaydı başarılı” → Otomatik login yapılır veya login sayfasına yönlendirilir; bu davranış tek bir yerde belgelenir ve UI metinleri buna göre ayarlanır.
- “Yetkisiz erişim” → Kullanıcı login değilse login ekranına; login ise yetkisiz ekran üzerinden ana modüllere geri dönebilir.
- “Dil değişimi” → Seçili dil local storage veya global Shell state’inde tutulur; yeniden yükleme sonrası da aynı dilde devam edilir.

## Interfaces (API / DB / Event)

### API
- `POST /api/auth/login` – Kullanıcı kimlik doğrulama.
- `POST /api/auth/register` – Yeni kullanıcı kaydı (varsa).
- `GET /api/auth/session` – Oturum durumu doğrulama (mevcut endpointlere göre uyarlanır).

### Database
- `users` tablosu – Kimlik bilgileri ve kullanıcı profili; bu Story sadece okuma/yazma akışını kullanır, şema değişikliğini kapsamaz.

### Events
- (Varsa) `user.registered` – Yeni kullanıcı kaydı sonrası yayılan event; bu Story içinde doğrudan kullanılmaz, yalnızca side-effect olarak göz önünde bulundurulur.

## Acceptance Criteria

Bu Story için güncel acceptance kriterleri:  
`docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`

## Definition of Done

- [x] İlgili SPEC maddeleri (varsa `docs/05-governance/06-specs/` altındaki dokümanlar) karşılanmış olmalı.  
- [x] Acceptance dosyasındaki tüm maddeler (`docs/05-governance/07-acceptance/E01-S05-Login.acceptance.md`) sağlanmış olmalı.  
- [x] İlgili ADR kararları uygulanmış olmalı (bkz. Dependencies bölümü).  
- [x] Kod, `docs/00-handbook/NAMING.md` ve stil kurallarına tam uyumlu olmalı.  
- [ ] Kod review’dan onay almış olmalı.  
- [ ] Unit + integration testleri yeşil olmalı.  
- [ ] `docs/05-governance/PROJECT_FLOW.md` üzerinde Son Durum ✔ Done olarak işlenmiş olmalı.

## Notlar

- Login rate-limit ve guardrail testleri ikinci fazda genişletilebilir; şu anki kapsam auth UI + i18n + Ant temizliği ile sınırlıdır.

## Dependencies

- ADR-003 – Auth and Broadcast Channel.
- ADR-008 – i18n and Dictionary Packaging.
- ADR-016 – Theme/Layout System (Shell tema slotları ve Tailwind-only yaklaşım).
- Backend auth-service sözleşmesi (`docs/03-delivery/api/auth.api.md`).

## Risks

- DE/ES sözlükleri EN fallback’i ile uzun süre senkron tutulmazsa, prod kullanıcıları için beklenmedik dil karışımları oluşabilir.
- Ant Design bağımlılıklarının kaldırılması sırasında, eski hata mesajları veya edge-case akışları gözden kaçabilir (özellikle unauthorized ve session timeout durumları).

## Flow / Iteration İlişkileri

Bu Story, ilk taslakta zaman kutulu bir plan altında düşünülmüştü. Yeni çalışma şeklinde bu dokümanlar kullanılmaz; Story’nin planlama ve ilerleme durumu yalnızca `docs/05-governance/PROJECT_FLOW.md` üzerinden takip edilir.

## İlgili Artefaktlar

- `backend/docs/05-governance/05-adr/ADR-008-i18n-and-dictionary-packaging.md`
- `backend/docs/05-governance/05-adr/ADR-016-theme-layout-system.md`

Kurallar:
- SP (Story Priority) sadece burada tanımlıdır; başka dokümanda değiştirilmez, sadece okunur.
- Story’nin detayı ayrı zaman-kutu dokümanlarında tekrar edilmez; yürütme bilgisi (ticket sırası, adımlar) `PROJECT_FLOW.md` üzerinden izlenir.
