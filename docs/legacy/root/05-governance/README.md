# 05-Governance Kök Yapısı (Roadmap + ADR)

Bu klasör, ürün/proje yol haritası ve mimari karar kayıtlarının tek kökünden sorumludur.

## 1. Kök Hiyerarşi

```text
docs/05-governance
│
├── PROJECT_FLOW.md          # Story akış durumu (mini Kanban + linkler; kanonik görünüm)
├── SPRINT_INDEX.md          # (Opsiyonel/legacy) Zaman kutulu deney arşivi; varsayılan akış PROJECT_FLOW.md üzerinden yönetilir
│
├── 01-epics/                # Her Epic için tek doküman (modül seviyesi)
│   ├── E01_Identity.md
│   └── ...
│
├── 02-stories/              # Her Story için tek doküman (tüm kapsam burada)
│   ├── E01-S05-Login.md
│   └── ...
│
├── 04-assets/               # Ortak görseller, diyagramlar, mockuplar
│   └── ...
│
├── 05-adr/                  # Architecture Decision Record’lar (neden böyle?)
│   ├── ADR-001-...
│   └── ...
│
├── 06-specs/                # Feature/teknik spec dokümanları (nasıl yaparız?)
│   └── ...
│
└── 07-acceptance/           # Epic/Story/Proje acceptance kriterleri (tek kaynak)
    └── ...

Ek olarak:
- `FEATURE_REQUESTS.md`      # Kullanıcı taleplerini topladığımız ve Story/Epic ile ilişkilendirdiğimiz hafif inbox listesi
```

`roadmap-legacy/` altındaki dosyalar geçiş/legacy alanıdır; aşağıda ayrıca açıklanır.

## 2. Öncelik Mimarisi (EP / SP / TP)

- Epic Priority (`EP`)
  - Tek kaynak: `01-epics/*.md` dokümanlarındaki Epic meta bloğunda belirtilen `Epic Priority` alanıdır.

- Story Priority (`SP`)
  - Tek kaynak: `02-stories/*.md` dosyalarındaki `Story Priority: <SP>` satırı.

- Ticket Priority (`TP`)
  - Tek kaynak: PROJECT_FLOW notları ve ilgili Story dokümanları; harici tracker kullanılmaz.

Özet:
- Stratejik öncelik → EP / SP (`01-epics/` + `02-stories/` + akış görünümü `PROJECT_FLOW.md`).
- Günlük iş sırası → TP (PROJECT_FLOW içindeki akış notları).
- Karar kayıtları → `05-adr/*` (öncelik tutmaz; yalnız referans alınır).

## 3. Roadmap Klasörü (roadmap-legacy/) – Geçiş Alanı

`docs/05-governance/roadmap-legacy/` altındaki içerik, eski roadmap yapısından kalan ve henüz tamamen taşınmamış alanları içerir:

- `acceptance/`: Kart bazlı acceptance dokümanları.
- `archive/`: Eski roadmap ve session-log snapshot’ları.
- `backlog.md`: Eski “doc backlog” — yeni fikirler doğrudan Story dokümanı (status=draft) olarak açılmalıdır.
- `session-log.md`: (Taşındı) Günlük oturum kayıtları artık `docs/00-handbook/session-log.md` altında tutulur; bu klasörde yalnız legacy snapshot’lar kalacaktır.

Yeni roadmap standardında:
- Epic/Story öncelikleri `01-epics/` + `02-stories/` + `PROJECT_FLOW.md` altında tutulur; zaman boyutu ve ticket sırası PROJECT_FLOW üzerinden izlenir.
- `roadmap-legacy/` kademeli olarak sadece arşiv alanı haline gelecektir.

Kullanıcı talepleri:
- Kullanıcıdan/işten gelen yeni talepler öncelikle `FEATURE_REQUESTS.md` inbox listesine kısa özet olarak eklenir.
- Talep netleştiğinde, ilgili Epic/Story dokümanı oluşturulur veya güncellenir (`02-stories/*.md` altında, `MP-STORY-FORMAT.md`’e göre) ve `FEATURE_REQUESTS.md` içindeki satıra Story ID eklenir.
- Detaylı gereksinim, kabul kriteri ve teknik tasarım her zaman Story + Acceptance + Spec dokümanlarında tutulur; `FEATURE_REQUESTS.md` yalnızca özet takip içindir.

## 4. ADR’ler ve Mimari Dokümanlarla İlişki

- `05-adr/ADR-0xx-*.md`:
  - “Neden bu kararı aldık?” sorusuna cevap verir (problem, alternatifler, karar, etkiler).
  - Öncelik tutmaz; Story/Sprint ve mimari dokümanlar ADR’lere referans verir.

- `docs/01-architecture/**`:
  - “Bugün sistem nasıl çalışıyor?” sorusunun cevabıdır (mevcut mimari).
  - Uygulaması biten ADR’lerin sonucunu burada anlatmak gerekir (gerektiğinde ADR’ye link verilerek).

Tipik akış:
1. Karar ihtiyacı → `05-adr/` altında ADR yaz.
2. Kararı hayata geçirecek işler → `PROJECT_FLOW.md` + `02-stories/` altında Story’lere yansıt (harici tracker yok).
3. İş tamamlanınca → ilgili `01-architecture/**` dokümanlarını, ADR’deki karara göre güncelle.

## 5. Specs ve Acceptance

- Story kabul kriterleri artık `07-acceptance/` altında tutulur; her Story için gerekiyorsa `E0X-SYY-*.acceptance.md` dosyası açılır.
- Story dokümanları (`02-stories/*.md`) acceptance kısmında yalnız ilgili dosyaya link verir; checklist tek kaynak olarak `07-acceptance/` içindedir.
- Legacy acceptance kayıtları `roadmap-legacy/archive/acceptance/` altından `07-acceptance/` altına taşınmıştır.
- Detaylı feature/teknik tasarım dokümanları `06-specs/` altında tutulur; ilgili Story dosyası bu spec’e link verir.
- Böylece Story + Spec + ADR + Acceptance, tek kök (`05-governance/`) altında, farklı ama net rollere sahip klasörlerde konumlanmış olur.
