# E03-S01-S01.1 — Accent / HC Görsel Tuning

**Durum:** Planned  
**Epic:** E03 – Theme & Layout System  
**İlişkili Story:** E03-S01-S01 – Accent Primary & Focus Var Yayma  
**İlişkili SPEC:** docs/05-governance/06-specs/SPEC-E03-S01-THEME-LAYOUT-V1.md  
**İlişkili ADR:** ADR-019-theme-ssot.md, ADR-016-theme-layout-system.md  
**Tarih:** 2025-12-06  

## Amaç
HC ve accent preset’lerinde primary/CTA/focus etkisini görsel olarak belirginleştirmek; yüksek kontrast hissini güçlendirmek. Sadece token/generator seviyesinde tuning ile bariz fark yaratmak.

## Kapsam
- Token: `frontend/design-tokens/figma.tokens.json` accent setlerinde primary/hover/focus/soft değerlerini artırılmış kontrastla güncelle. HC/dark görünümde okunabilirlik netleşsin.  
- Generator: Değişen accent token’larını CSS var’larına yansıtmak için yeniden üretim (script).  
- UI: Ek kod değişikliği beklenmez; accent var’larını kullanan mevcut stiller daha bariz hale gelecek.

## Acceptance
1) Preset turu (Açık → Mor → Zümrüt → Gün Batımı → Okyanus → Grafit → HC) sırasında primary/focus renkleri gözle görülür biçimde değişir; HC’de belirgin kontrast hissi oluşur.  
2) HC modunda primary buton + focus ring yüksek kontrastlı (açık metin / parlak accent).  
3) `theme.css` accent blokları yeni değerleri içerir (primary/hover/focus/soft).  
4) Kodda ek stil değişikliği yapılmadan yalnız token/generator güncellemesiyle fark yakalanır (wiring aynı kalır).

## Notlar
- Amaç: “mini tuning” – doküman/sözleşme değişmeden yalnız token/generator düzeltmesi.  
- Sonraki dilimler (axes/overlay) için ayrı story’ler kullanılacak.
