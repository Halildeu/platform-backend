---
title: Documentation Workflow
status: draft
owner: "@team/platform-docs"
last_review: YYYY-MM-DD
---

## 1. Yazım Süreci
1. **Başlat** – İhtiyaca göre doğru şablonu (`docs/03-delivery/templates/`) kopyala.
2. **Taslak** – Metadata (`status`, `owner`, `last_review`) alanlarını doldur, CODEOWNERS kapsamında reviewer ekle.
3. **Review** – PR’da teknik inceleme + doc incelemesi yapılır. Gerektiğinde RFC → ADR akışı izlenir.
4. **Yayınla** – Onay sonrası `status: published`, `last_review` güncellenir, ilgili index/roadmap’e link ekle.

## 2. Güncel Tutma
- **Doc hygiene**: Düzenli aralıklarla roadmap/ADR ve kritik runbook’lar gözden geçirilir; yeni modelde `docs/05-governance/PROJECT_FLOW.md` + ilgili `01-epics/` ve `02-stories/` kontrol edilir (geçiş sürecinde legacy backlog için `docs/05-governance/roadmap-legacy/backlog.md` güncellenir).
- **Stale raporu**: CI pipeline’ına eklenecek script, 180 günden uzun süredir dokunulmayan kritik dokümanları raporlayacak.
- **Changelog**: Büyük kararlar ve release’ler `docs/03-delivery/guides/releases/` altında arşivlenir.

## 3. Kategoriler & Sahiplik
- Kategoriler `docs/README.md` içinde listelenmiştir.
- CODEOWNERS zorunlu reviewer belirler; onay olmadan merge edilmez.
- Ekip değişikliklerinde CODEOWNERS ve metadata güncellenmelidir.

## 4. Araçlar
- Markdown lint & broken link checker (CI guardrail)
- `npm run i18n:pull` / `i18n:pseudo` script’leri → i18n dokümanlarının güncelliği
- Backstage (planlanıyor) → Runbook ve servis dokümanlarının kataloglanması
- `docs/03-delivery/guides/a11y-checklist.md` → SR/keyboard/kontrast checklist + axe smoke referansı

## 5. Sık Sorulanlar
- **Yeni kategori gerekli mi?** Yeni bir ihtiyacı önce uygun Story altında takip et; yalnızca doküman kategorisi ekleme ihtiyacı varsa geçiş sürecinde `docs/05-governance/roadmap-legacy/backlog.md` içine ekle, hygiene toplantısında karar verelim.
- **Dokümanı kim güncelleyecek?** Metadata `owner` alanı sorumludur; yoksa CODEOWNERS’daki ekip devralır.
- **Stale gördüm, PR açacak vaktim yok.** Uygun Story yoksa geçiş sürecinde `docs/05-governance/roadmap-legacy/backlog.md` satırı ekleyip “help wanted” notu bırak.

## 6. Permission Registry Guardrail’i
`docs/05-governance/permission-registry/permissions.registry.json` tek kaynaktır. Güncelleme akışında şu adımlar zorunludur:

1. JSON dosyasını güncelledikten sonra `node scripts/permissions/generate-frontend-permission-registry.mjs` komutu ile Access MFE’ye ait `permissionRegistry.generated.ts` dosyasını yeniden üret. Bu dosya commit’te yer almalıdır.
2. `node scripts/permissions/validate-permission-registry.mjs` komutu ile manifest/permission constant senkronunu doğrula (CI aynı komutu çalıştırır, PR öncesi lokale almak hatayı erken yakalar).
3. Registry değişikliği Access MFE veya ilgili runbook’ı etkiliyorsa Story/Acceptance referanslarını güncelle ve session-log’a kayıt düş.
4. PR açıklamasına “Permission registry güncellendi, generator + validator çalıştırıldı” notunu ekle (PR şablonuna yeni checkbox eklendi).
