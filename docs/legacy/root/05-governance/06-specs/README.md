# 06-Specs – Feature / Teknik Tasarım Dokümanları

Bu klasör, Epic/Story’leri koda dökerken ihtiyaç duyulan **feature / teknik spec** dokümanlarını toplar. Amaç:

- Story’deki “ne yapıyoruz?” bilgisini, burada “nasıl yapıyoruz?” detayına çevirmek.
- API, veri modeli, edge case, test planı gibi ayrıntıları tek yerde toplamak.
- İlgili ADR, mimari ve standart dokümanlarına buradan referans vermek.

## Nerede Konumlanıyor?

- İşin tanımı ve kabul kriterleri → `docs/05-governance/01-epics/` ve `docs/05-governance/02-stories/`
- Kararın kendisi (neden böyle?) → `docs/05-governance/05-adr/`
- Detaylı uygulama tasarımı → **`docs/05-governance/06-specs/`**

Tipik akış:
1. Story yazılır (`02-stories/...`).
2. Gerekliyse bu klasörde bir spec açılır (`docs/05-governance/06-specs/SPEC-<STORY-ID>-<kısa-konu>.md`) ve Story’den linklenir.
3. Spec içinde:
   - İlgili ADR referansları (`docs/05-governance/05-adr/...`)
   - Mimari doküman linkleri (`docs/01-architecture/**`)
   - Uygulanacak standartlar (`docs/agents/**`)
   - Gerekliyse CI/env/test rehberlerine linkler (`docs/03-delivery/**`)

## Şablon

Yeni bir spec için başlangıç noktası: `docs/05-governance/06-specs/MP-SPEC-FORMAT.md`  
Bu dokümandaki meta blok ve bölüm yapısı doğrudan yeni dosyaya uygulanır (`docs/05-governance/06-specs/SPEC-<STORY-ID>-<kısa-konu>.md`), ekstra boş şablon gerekmez.
