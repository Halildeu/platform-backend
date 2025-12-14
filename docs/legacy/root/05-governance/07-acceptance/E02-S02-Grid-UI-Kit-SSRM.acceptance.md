---
title: "Acceptance — E02-S02 Grid UI Kit SSRM"
status: done
related_story: E02-S02
---

Story ID: E02-S02-Grid-UI-Kit-SSRM

Checklist
- [x] UI Kit `EntityGridTemplate` SSRM param sözleşmesini tek yerden uygular. (buildEntityGridQueryParams; Users/Reporting datasource’ları helper üzerinden sayfa/sort/search/advancedFilter gönderiyor.)
- [x] Users ve Reporting MFE grid ekranları EntityGridTemplate’e taşınmış ve legacy grid konfigleri kaldırılmıştır. (Users & Reporting grid’leri ortak helper + tema var(`--table-surface-bg`) ile çalışıyor.)
- [x] Backend tarafında advancedFilter whitelist + çoklu ORDER BY desteği prod’da aktiftir (BE-01, FE-01 ile uyumlu). (user-service parseSort whitelist + buildAdvancedFilterSpecSafe; invalid* durumunda 400.)
- [x] `EntityGridTemplate` tema/appearance/density eksenlerini (`shell-light`, `shell-dark`, `shell-hc`, `shell-compact`) destekler; satır yüksekliği, header/pinned ayrımı, zebra ve gridline davranışları `docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md` ve `docs/05-governance/02-stories/E03-S01-Theme-Layout-System.md` altındaki kurallarla uyumludur. (Grid yüzeyi var(--table-surface-bg); density/appearance data-attr ile iletiliyor.)

Kanıt
- FE lint’ler: `npm run lint:semantic`, `npm run lint:style`, `npm run lint:tailwind` → PASS.
- FE test: `npm run test:variants` (mfe-users workspace) → PASS; SSRM helper + variant preference akışı yeşil.
