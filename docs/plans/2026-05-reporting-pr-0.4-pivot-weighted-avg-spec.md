# PR-0.4 — Pivot Mode + Weighted AVG (Cross-Schema)

> **Status**: Spec draft — owner UX feedback bekliyor. Plan v2.1
> follow-up; Plan v2.1 PR-0 zincirinin (PR-0.1 ↔ PR-0.5) son
> teknik kapsamı bu PR'da net olarak belirlenir.
>
> **Authors**: Claude (autonomous mode, 2026-05-07).
>
> **Cross-AI peer review**: Codex thread `019e0119-7c9d-7541-8059-
> f9553c3303ce` iter-3 PARTIAL — implementation-time tek tek karar
> noktaları açık. Spec onayı = owner; implementation kararları =
> Codex post-impl review.
>
> **Status flag (capability envelope)**: Bu spec PR'ı **runtime
> capability flip etmez**. `serverSidePivoting` flag'i **PR-0.4d**
> (controller + capability flip + frontend integration) merge'i
> sırasında flip'lenecek. Şu an `false` (PR-0.1 envelope:
> `serverSideGrouping=true`, `serverSidePivoting=false`,
> `serverSideAggregation=true`).

---

## 0. TL;DR — Karar Götürülecek 7 Soru

| # | Soru | Default önerisi | Alternatif |
|---|---|---|---|
| 1 | İlk pivot capability hangi rapor(lar)da açılacak? | `fin-muhasebe-detay` only (tek rapor canary) | Capability-driven (her opt-in raporda otomatik) |
| 2 | Weighted AVG semantiği nedir? | `SUM(value * weight) / NULLIF(SUM(weight), 0)` (ANSI standart) | Domain-specific denominator (örn. `RECORD_COUNT` ya da `WORKING_DAYS`) |
| 3 | Null/zero weight bucket davranışı? | `NULL` döndür (NULLIF güvenli) | Sentinel bucket (`'no-weight'`) ya da bucket drop |
| 4 | Multi-level groupKeys + pivot beraber çalışacak mı? | **Hayır** — PR-0.4 single-level row group + single pivot dim. Multi-level + pivot **PR-0.4e follow-up** (engine + UI kontratı yeşillendikten sonra). | Multi-level + pivot beraber (Workcube use case istiyor — ama blast radius yüksek) |
| 5 | Export/count/pagination semantiği? | Pivot-applied result set üzerinde aynı pipeline (CSV/Excel için flatten) | Pivot off-mode export only (UX'te disable) |
| 6 | Capability contract: frontend pivot UI ne zaman açık olacak? | `capabilities.serverSidePivoting === true` olan raporlarda | Hep açık, server `501 pivot_not_supported` döner |
| 7 | Acceptance: yeni MSSQL Testcontainers IT? | Evet — `SqlBuilderMssqlIntegrationTest` **5 yeni test** | Sadece unit test (PR-0.5 hardening yetersiz olur) |

> **Codex iter-4 revize**: Q4 default'u `evet` → `hayır` çevrildi. Workcube use case (ACCOUNT_CODE → BRANCH_NAME × ACTION_TYPE) gerçek; ama multi-level + pivot aynı anda groupKeys parsing, ancestor filter, SSRM expansion, secondary columns, count/pagination ve sort kontratını birden büyütüyor. İlk canary için blast radius gereksiz artıyor. Owner "mutlaka beraber" kararı verirse §3 API + §6 out-of-scope + §11 breakdown aynı anda yeniden çizilir.

---

## 1. Bağlam

### 1.1 Plan v2.1 referansları

- Plan PR #75 ([docs/plans/2026-05-reporting-platform-hardening.md](./2026-05-reporting-platform-hardening.md)):
  - "Pivot deferred to a follow-up PR. DTO carries `pivotMode`/`pivotCols` fields for forward-compat, but the server returns `501 pivot_not_supported` and the frontend disables pivot UI."
  - "Weighted AVG across multi-schema (was: naive AVG). When UNION ALL crosses year schemas, `AVG(AVG(x))` is wrong. Backend carries hidden `SUM(x)` + `COUNT(x)` and computes outer `SUM(sum) / SUM(count)`. SUM/MIN/MAX/COUNT compose naturally."
- Capability flip pattern: Plan v2.1 PR #82 + #86 (`fin-muhasebe-detay` row-group capability açıldı). Pivot flag aynı dosyada (`reports/<key>.json` içine `capabilities.serverSidePivoting=true`).

### 1.2 Halihazırda yerleşik altyapı

- `POST /api/v1/reports/{key}/query` (PR #78) — DTO `pivotMode` + `pivotCols` field'ları forward-compat şeklinde mevcut.
- `SqlBuilder.buildGroupedQuery(...)` (PR #79/#81) — single-level + multi-level GROUP BY, value column allowlist, `_rowCount` column emission.
- `YearlySchemaResolver` (Plan v2.1 §7) — UNION ALL across `workcube_mikrolink_<year>_<companyId>` schemas. Mevcut SqlBuilder raw `UNION ALL` + tek dış aggregate pattern'i `AVG(AMOUNT)` gibi simetrik aggregator'lar için doğru sonuç verir. PR-0.4 yeni `weightedAvg` aggregator'ı eklerken numerator/denominator kontratını **branch-level pre-aggregation'a kaymadan** raw stream üzerinden taşıması gerekir (§3.4'e bak).
- `ReportCapabilities` envelope — `serverSidePivoting=false` flag PR-0.1'de basıldı.
- MSSQL Testcontainers IT (PR #88) — yeni capability'ler için aynı pattern üzerinden test eklenebilir.

### 1.3 Bilinen pivot kullanım caseleri (Workcube)

- `fin-muhasebe-detay`:
  - Row group: `ACCOUNT_CODE` (level-1) → `BRANCH_NAME` (level-2)
  - Pivot dim: `ACTION_TYPE` (BORÇ vs ALACAK kolonları)
  - Value: `AMOUNT` (sum) ve `AMOUNT_CURRENCY` (sum)
- `proje-bazli-maliyet`:
  - Row group: `PROJECT_NAME`
  - Pivot dim: `MONTH_NAME` (12 kolon — Ocak..Aralık)
  - Value: `COST_AMOUNT` (sum) + `WORKING_HOURS` (sum)
- Çok sayıda dashboard widget aynı pattern — backend pivot mode flip'i UI'a "yıllık matris" tablo açar.

---

## 2. Frontend (AG Grid Server-Side Row Model — Pivot)

### 2.1 Capability gate

- `useReportCapabilities(reportKey)` hook'undan dönen `serverSidePivoting`.
- `false` → AG Grid `pivotMode=false` zorla, "Pivot Mode" toggle disabled.
- `true` → toolbar'da pivot toggle aktif, `pivotPanelShow="always"`.

### 2.2 Pivot dim seçimi

- AG Grid columnDef'inde `enablePivot=true` olan kolonlar pivot dim olarak sürüklenebilir.
- Maksimum **1** pivot dim (PR-0.4 kapsamı). Multi-pivot → out of scope (yeni PR'da).
- Backend `pivotCols` array uzunluğu > 1 → `400 multi_pivot_not_supported`.

### 2.3 Value cols

- `aggFunc` PR-0.4 öncesi: `sum` (PR #79/#81). Bu PR ekler: `avg`, `min`, `max`, `count`.
- Default `sum` korunur (geri uyumlu).

### 2.4 Request body (pivot path — single-level row group, single pivot dim)

```json
POST /api/v1/reports/fin-muhasebe-detay/query
{
  "pivotMode": true,
  "rowGroupCols": [{"id": "BRANCH_NAME"}],
  "groupKeys": [],
  "pivotCols": [{"id": "ACTION_TYPE"}],
  "valueCols": [{"id": "AMOUNT", "aggFunc": "sum"}],
  "filterModel": {},
  "sortModel": [],
  "startRow": 0,
  "endRow": 100
}
```

> Single-level (PR-0.4 scope): `groupKeys=[]` ilk seviye expansion. Multi-level (rowGroupCols çok-eleman + groupKeys ancestor path) **PR-0.4e**'de.

### 2.5 Response shape (pivot row)

```json
{
  "rows": [
    {
      "BRANCH_NAME": "İstanbul",
      "p0_AMOUNT_sum": 12345.67,
      "p1_AMOUNT_sum": 9876.54,
      "_rowCount": 42
    }
  ],
  "pivotResultFields": [
    {"colId": "p0_AMOUNT_sum", "field": "p0_AMOUNT_sum", "headerName": "BORÇ / Tutar"},
    {"colId": "p1_AMOUNT_sum", "field": "p1_AMOUNT_sum", "headerName": "ALACAK / Tutar"}
  ]
}
```

- Pivot column **safe alias** ($§3.1.1): `p<index>_<valueColField>_<aggFunc>` (regex `[A-Za-z][A-Za-z0-9_]*`).
- `headerName` UX label: `<pivotDisplayValue> / <valueColDisplayName>` (Türkçe karakter, boşluk, `/` desteklenir — sadece UX-side label).
- `pivotResultFields` AG Grid'in pivot result column entegrasyonu için gerekli. Implementation API'si AG Grid versiyonuna göre: v32 `gridApi.setPivotResultColumns(...)`, v30 `gridApi.setSecondaryColumns(...)`. Frontend impl PR-0.4d sırasında package.json'daki AG Grid version'a göre net seçilir; spec API ismini kilitlemez.

---

## 3. Backend (SqlBuilder pivot extension)

### 3.1 SqlBuilder API genişletme

```java
public BuiltQuery buildPivotedGroupedQuery(
    ReportDefinition def,
    YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
    List<String> visibleColumns,
    String rowGroupCol,                  // single-level (PR-0.4 scope)
    List<String> groupKeys,              // expanded path (single-level → at most 1 entry)
    String pivotCol,                     // single pivot dim
    List<PivotValue> pivotValues,        // static or discovery-resolved
    List<GroupedAggregation> aggs,       // sum/avg/min/max/count
    Map<String, Object> filterModel,
    List<Map<String, String>> sortModel,
    String rlsWhere,
    Map<String, Object> rlsParams,
    Integer startRow, Integer endRow);
```

> Multi-level row group + pivot **PR-0.4 scope dışı** (Codex iter-4 §1: ancestor filter + SSRM expansion + count/pagination/sort kontratı aynı anda büyüyor). PR-0.4e follow-up'ı: `rowGroupCols: List<String>` + `groupKeys: List<Object>` parametrize.

### 3.1.1 Pivot value record

```java
public record PivotValue(
    String safeAlias,        // canonical opaque alias (e.g. "p0_AMOUNT_sum")
    Object rawValue,         // pivot column raw value (binds via :pivot0)
    String valueColField,    // referenced value column id
    String aggFunc,          // sum/avg/min/max/count/weightedAvg
    String displayName       // human-readable for grid header
) {}
```

Codex iter-4 §5 absorb: alias raw pivot value'dan **doğrudan türetilmez**. `<pivotValue>_<valueCol>_<aggFunc>` UX-side label'dır, SQL alias değildir. SQL alias canonical safe pattern `p<index>_<valueColField>_<aggFunc>` (regex `[A-Za-z][A-Za-z0-9_]*` uyumlu) — Türkçe karakter, boşluk, `]`, `/`, duplicate normalized value, case-insensitive collation collision, uzun alias riski hep ortadan kalkar.

### 3.2 Generated SQL (single-level + single-pivot dim)

T-SQL pivot, native `PIVOT` keyword **kullanılmaz** (dinamik kolon listesi → CASE WHEN aggregation pattern). Pivot predicate **bind parameter** ile push (literal interpolation YASAK — Codex iter-4 §5 + injection guard).

```sql
SELECT
  [BRANCH_NAME],
  SUM(CASE WHEN [ACTION_TYPE] = :pivot0 THEN [AMOUNT] ELSE 0 END) AS [p0_AMOUNT_sum],
  SUM(CASE WHEN [ACTION_TYPE] = :pivot1 THEN [AMOUNT] ELSE 0 END) AS [p1_AMOUNT_sum],
  COUNT_BIG(*) AS _rowCount
FROM (
  -- sourceQuery (yearly UNION ALL — branch-level pre-aggregation YOK)
) AS _src
WHERE _src.[ACCOUNT_CODE] = :groupKey0
GROUP BY [BRANCH_NAME]
ORDER BY [BRANCH_NAME] ASC
OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY;
```

Pivot value bindings: `params.put("pivot0", "BORÇ"); params.put("pivot1", "ALACAK");`

Frontend secondary column metadata (`pivotResultFields`):
```json
[
  {"colId": "p0_AMOUNT_sum", "headerName": "BORÇ / Tutar", "field": "p0_AMOUNT_sum"},
  {"colId": "p1_AMOUNT_sum", "headerName": "ALACAK / Tutar", "field": "p1_AMOUNT_sum"}
]
```

### 3.2.1 `_rowCount`, `COUNT(*)`, `COUNT(value)` semantiği (Codex iter-4 §3 + iter-5 Q1 absorb)

| Metric | Definition | Use |
|---|---|---|
| `_rowCount` | Row group bucket içindeki kaynak satır sayısı, **filters/RLS/groupKeys uygulandıktan sonra**, pivot cell'lerden bağımsız | Pagination total = `COUNT_BIG(*)`; bucket cardinality summary |
| `COUNT(value)` agg func **(default for `aggFunc=count`)** | Row group bucket × pivot cell'e düşen satır sayısı, NULL value satırları **dahil değil** | Mevcut SqlBuilder grouped builder davranışıyla uyumlu — value column null-skip count |
| `COUNT(*)` agg func | Row group bucket × pivot cell'e düşen satır sayısı, NULL value satırları dahil | İleride `aggFunc=countRows` opt-in (PR-0.4 scope **dışı** — geri uyum disiplini) |

> Codex iter-5 Q1 absorb: `aggFunc=count` default'u `COUNT([valueCol])` (null-skip) olur — mevcut grouped builder davranışını korur, value column seçiminin anlamı kaybolmaz, SQL-native NULL handling. `COUNT(*)` istenirse PR-0.4 sonrası ayrı `aggFunc=countRows` ile yeni opt-in. Bu kararla davranış değişikliği çıkmıyor; pivot cell'lerinin SUM/MIN/MAX/AVG/COUNT ailesine eşit hizalı kalır.

`_rowCount` always emitted, value column lifecycle'ından bağımsız.

### 3.2.2 ORDER BY pivot result allowlist (Codex iter-4 §4 absorb)

`sortModel.field` allowlist:

1. Row group field (örn. `BRANCH_NAME`) — `def.columns` allowlist'inde
2. Generated `pivotResultFields[*].colId` (örn. `p0_AMOUNT_sum`)

`ACTION_TYPE` raw, `AMOUNT` raw, ya da client-uydurulmuş alias **kabul edilmez** (`400 sort_field_not_allowlisted`).

Stable pagination tie-breaker: pivot result alias üzerinden sort yapılırsa ORDER BY suffix olarak row group key eklenir.

```sql
-- sortModel: [{"colId":"p0_AMOUNT_sum","sort":"desc"}]
ORDER BY [p0_AMOUNT_sum] DESC, [BRANCH_NAME] ASC
OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY
```

### 3.3 Pivot value discovery

İki strateji opt-in (`reports/<key>.json` config):

| Strategy | Davranış | Trade-off |
|---|---|---|
| `static` | `pivotValues` listesi report config'inde sabit | Predictable; yeni değer için config update |
| `discovery` | İlk SELECT `DISTINCT pivotCol` ile değerleri çek, ikinci SELECT pivot CASE WHEN | 2 query (bir pre-flight + bir aggregation); cardinality cap (örn. 50) zorunlu |

Default: `static` (predictable). `discovery` yüksek-cardinality riskine sahip rapor için opsiyonel.

`discovery` mode'da cap aşımında `400 pivot_cardinality_exceeded` (cap configurable per-report).

### 3.3.1 Discovery query parity contract (Codex iter-4 §6 absorb)

Discovery query **aynı scope** üzerinde çalışmalı (data query ile filterModel + RLS + ancestor groupKeys parity):

```sql
SELECT DISTINCT TOP (:cap + 1) [ACTION_TYPE]
FROM (
  -- aynı sourceQuery (yearly UNION ALL)
) AS _src
WHERE _src.[ACCOUNT_CODE] = :groupKey0
  -- aynı filterModel injected
  -- aynı RLS WHERE clause
ORDER BY [ACTION_TYPE] ASC;
```

`TOP (:cap + 1)` deterministic: `cap=50` ise 51 satır → `400 pivot_cardinality_exceeded`. Result < cap → static-list olarak data query'ye injected.

`ORDER BY` deterministic — discovery sırası secondary column order'ını belirler (frontend pivot kolonlarının soldan sağa dizilişi).

Eğer parity sağlanmazsa: secondary columns grid'de gözükür ama current bucket'ta karşılığı yok (boş kolon), ya da data'da gelen pivot value column metadata'da eksik → grid render error. **Bu yüzden discovery + data query exact aynı `_src` + filterModel + RLS + groupKeys** zorunlu.

### 3.4 Weighted AVG (cross-schema)

#### 3.4.1 Bağlam (Codex iter-4 §2 absorb — re-framed)

Mevcut `SqlBuilder` multi-schema akışı raw satırları `UNION ALL` edip dış sorguda aggregate ediyor. Bu form `AVG(AMOUNT)` için **doğru** sonuç verir; `AVG(AVG(x))` bug'ı **mevcut kod tabanında yok**.

Bu spec'in kapsamı: PR-0.4 implementation **branch-level pre-aggregation'a kaymamalı**. `weightedAvg` aggregator karışık SUM(value × weight) + SUM(weight) numerator/denominator carry'sini per-schema yapıp dış katmanda toplamaya kaymak **tasarım hatası** olur.

**Doğru kontrat**: `weightedAvg` ve `pivot` implementation'ı raw `UNION ALL` üzerinden tek dış aggregate katmanında çalışır:

#### 3.4.2 Düzeltme

Implementation kontratı: numerator + denominator **kaynaktan akar**, branch-level pre-aggregation YOK.

```sql
-- DOĞRU: raw UNION ALL akışı + tek dış aggregate
SELECT [BRANCH_NAME],
       SUM([RATE] * [WORKING_DAYS]) / NULLIF(SUM([WORKING_DAYS]), 0) AS [RATE_weightedAvg],
       COUNT_BIG(*) AS _rowCount
FROM (
  SELECT [BRANCH_NAME], [RATE], [WORKING_DAYS]
  FROM [workcube_mikrolink_2025_35].[PROJECT_RATES] WITH (NOLOCK)
  WHERE 1=1 -- filters/RLS/groupKeys
  UNION ALL
  SELECT [BRANCH_NAME], [RATE], [WORKING_DAYS]
  FROM [workcube_mikrolink_2026_35].[PROJECT_RATES] WITH (NOLOCK)
  WHERE 1=1
) _src
GROUP BY [BRANCH_NAME];
```

**Yasak pattern** (denominator'ı kaybeden branch-level average — AVG(branch_avg) hatası):

```sql
-- YANLIŞ: per-branch weighted average dış AVG ile eşit ağırlıklı toplanıyor
-- → cross-schema cardinality farkı bias yaratır
SELECT BRANCH_NAME,
       AVG(_branch_weighted_avg) AS RATE_weightedAvg
FROM (
  SELECT BRANCH_NAME,
         SUM(RATE * WORKING_DAYS) / NULLIF(SUM(WORKING_DAYS), 0) AS _branch_weighted_avg
  FROM [workcube_mikrolink_2025_35].[PROJECT_RATES] WITH (NOLOCK)
  GROUP BY BRANCH_NAME
  UNION ALL
  SELECT BRANCH_NAME,
         SUM(RATE * WORKING_DAYS) / NULLIF(SUM(WORKING_DAYS), 0)
  FROM [workcube_mikrolink_2026_35].[PROJECT_RATES] WITH (NOLOCK)
  GROUP BY BRANCH_NAME
) _u
GROUP BY BRANCH_NAME;
```

Bu pattern her branch'in weighted average'ını hesaplar, sonra outer `AVG()` ile **eşit ağırlıklı** toplar. 2025'te 1 satır + 2026'da 99 satır olan bir bucket'ta bu pattern outer AVG = `(avg_2025 + avg_2026) / 2`; oysa doğru weighted average tüm 100 satırı tek havuzda işler. Cross-schema cardinality farkı bias üretir.

> Cebirsel olarak doğru olan branch-level pre-aggregation `SUM(numerator) / SUM(denominator)` carry (yani her branch'ten `SUM(RATE * WORKING_DAYS)` + `SUM(WORKING_DAYS)` taşıyıp dış katmanda `SUM(_n) / NULLIF(SUM(_d), 0)`) sonuç olarak **doğru** hesaplar. Yine de PR-0.4 implementation'ı **raw UNION + tek dış aggregate** kararında kalsın çünkü filter/RLS/groupKey scope hizalaması ve aggregator semantic compose'u (sum/avg/min/max/count/weightedAvg) tek katmanlı kalır; iki katmanlı carry pattern'i yeni aggregator eklendiğinde inconsistent davranabilir. (Codex iter-5 §4 absorb: "raw UNION + outer aggregate" disiplini ifade tercihi, denominator'ı kaybeden AVG-of-AVG patterni tek somut yasaklı pattern.)

`SUM`, `MIN`, `MAX`, `COUNT` doğal compose: raw UNION ALL üzerine dış aggregate yeterli. `weightedAvg` da numerator/denominator'ı raw stream'den taşıyarak aynı pattern.

#### 3.4.3 Custom weight

`reports/<key>.json` per-report opt-in:

```json
{
  "valueCols": [
    {
      "field": "RATE",
      "aggFunc": "weightedAvg",
      "weight": "WORKING_DAYS"
    }
  ]
}
```

Generated SQL (raw UNION + tek dış aggregate):

```sql
SELECT [BRANCH_NAME],
       SUM([RATE] * [WORKING_DAYS]) / NULLIF(SUM([WORKING_DAYS]), 0) AS [RATE_weightedAvg],
       COUNT_BIG(*) AS _rowCount
FROM (
  SELECT [BRANCH_NAME], [RATE], [WORKING_DAYS]
  FROM [workcube_mikrolink_2025_35].[PROJECT_RATES] WITH (NOLOCK)
  WHERE 1=1 -- filters/RLS/groupKeys
  UNION ALL
  SELECT [BRANCH_NAME], [RATE], [WORKING_DAYS]
  FROM [workcube_mikrolink_2026_35].[PROJECT_RATES] WITH (NOLOCK)
  WHERE 1=1
) _src
GROUP BY [BRANCH_NAME];
```

Null/zero weight (`Q3` kararı):
- `RATE * WORKING_DAYS` → SQL Server `NULL * x = NULL`, `0 * x = 0` (deterministic).
- `NULLIF(SUM(weight), 0)` denominator zero → result NULL (sentinel bucket'tan iyi UX, default `Q3.NULL`).
- Owner alternatif önerirse: sentinel bucket veya bucket drop için ayrı flag (`weightZeroBehavior`).

### 3.5 Capability contract genişletme

`ReportCapabilities` (PR-0.1 envelope):

```ts
interface ReportCapabilities {
  serverSideGrouping: boolean;
  serverSidePivoting: boolean;       // PR-0.4 flips for opt-in reports
  serverSideAggregation: boolean;
  supportedAggFuncs: string[];       // "sum","avg","min","max","count","weightedAvg"
  pivotDimMaxCardinality?: number;   // discovery mode cap
}
```

Frontend kontrol:
```ts
const canPivot = caps.serverSidePivoting === true;
const supportsWeightedAvg = caps.supportedAggFuncs.includes("weightedAvg");
```

---

## 4. Failure semantics

| Status | Body code | When |
|---|---|---|
| `400` | `multi_pivot_not_supported` | `pivotCols.length > 1` |
| `400` | `pivot_dim_not_pivotable` | `pivotCols[0].id` config'de `pivotable=false` veya yok |
| `400` | `pivot_cardinality_exceeded` | `discovery` mode + cap aşımı |
| `400` | `weighted_avg_weight_missing` | `aggFunc=weightedAvg` ama `weight` field tanımsız |
| `501` | `pivot_not_supported` | `capabilities.serverSidePivoting=false` |
| `501` | `weighted_avg_not_supported` | `capabilities.supportedAggFuncs` içinde yok |

---

## 5. Test plan (acceptance)

### 5.1 Unit (SqlBuilderTest, JUnit 5)

| Test | Beklenen SQL pattern |
|---|---|
| `buildPivotedGroupedQuery_singleLevelStaticPivotValues` | `SUM(CASE WHEN [pivotCol] = :pivot<idx> THEN [val] ELSE 0 END) AS [p<idx>_<valueColField>_<aggFunc>]` × N (bind-param + safe alias contract) |
| `buildPivotedGroupedQuery_dynamicPivotValuesAfterPreFlight` | İlk SELECT `DISTINCT pivotCol`, ikinci SELECT pivot |
| `buildPivotedGroupedQuery_rejectsMultiPivotDim` | `IllegalArgumentException` |
| `buildWeightedAvgGroupedQuery_carriesWeightSumAndProductSum` | `SUM(val * weight)` + `SUM(weight)` her UNION branch |
| `buildWeightedAvgGroupedQuery_outerAggDividesSumByWeightSum` | `SUM(_sum_x_weight) / NULLIF(SUM(_sum_weight), 0)` |

### 5.2 Integration (SqlBuilderMssqlIntegrationTest, MSSQL Testcontainers — 5 yeni IT)

| Test | Senaryo |
|---|---|
| `buildPivotedGroupedQuery_overRealMssql_caseSumsCorrectly` | 2 pivot value × 3 row group bucket = 6 hücre matrix; safe alias map BigDecimal precision |
| `buildPivotedGroupedQuery_dynamicDiscoveryMatchesStaticOutput` | Discovery mode == static mode equivalence (cardinality < cap); aynı `_src` + filterModel + RLS + groupKeys parity |
| `buildWeightedAvgGroupedQuery_crossSchemaReturnsWeightedAverage` | **Positive correctness**: eşitsiz schema cardinality + weight dağılımında beklenen `SUM(v*w)/SUM(w)` BigDecimal precision ile doğrula |
| `crossSchemaWeightedAvg_fixtureWouldExposeAverageOfAveragesBias` | **Oracle sanity / regression sensitivity**: aynı fixture üzerinde naive `AVG(per_year_avg)` hesabının doğru sonuçtan ≥%5 saptığını göster (production SQL davranışı **DEĞİL**, fixture kalitesi kanıtı; Codex iter-4 §9 absorb) |
| `buildWeightedAvgGroupedQuery_zeroWeightProducesNull` | `NULLIF(SUM(weight),0)` denominator 0 → NULL döndür (Q3 default) |

> Codex iter-4 §9 ayrım: birinci test üretim path'inin doğru davrandığını kanıtlar; ikinci test fixture'ın gerçekten bug'ı yakalayacak şekilde tasarlandığını kanıtlar (regression guard sensitivity). Üretim SQL'inde naive AVG path'i yazılı **değil** — sadece test fixture'da synthetic karşılaştırma.

PR #88'in `TEST_SCHEMA = "workcube_mikrolink_2026_35"` pattern'i devam eder; cross-schema test için ikinci schema ekle (`workcube_mikrolink_2025_35`).

### 5.3 Frontend (Vitest + React Testing Library)

| Test | Senaryo |
|---|---|
| `ReportGrid_pivotMode_disabledWhenCapabilityFalse` | `serverSidePivoting=false` → pivot toggle disabled |
| `ReportGrid_pivotMode_pivotResultColumnsRendered` | `pivotResultFields` from response → AG Grid pivot result column API (versiyon-bağımlı: `setPivotResultColumns` v32 / `setSecondaryColumns` v30) çağrılı |
| `ReportGrid_pivotMode_aggregateFunctionDropdownShowsWeightedAvg` | `supportedAggFuncs` include `weightedAvg` → dropdown'da gözük |

---

## 6. Out of scope (gelecek PR)

- **Multi-pivot dim** (örn. `MONTH × ACTION_TYPE` cross product)
- **Pivot + multi-level row group beraber**: PR-0.4 tek-level row group + tek pivot dim. Multi-level + pivot **PR-0.4e follow-up** (Codex iter-4 §1: blast radius azaltma).
- **Aggregate filter (HAVING)**: Plan v2.1 v3 revize'sinde defer'lendi. Burada da out of scope.
- **Subtotal rows** (Excel pivot subtotal column'larına eşdeğer): `_rowCount` already; ama group total row açma frontend gridOptions detayı.
- **Pivot CSV/Excel export ek formatı**: Pivot-applied response shape üzerinde aynı flatten pipeline çalışacak (Q5 default); gerçek Excel pivot table formula export ayrı PR.

---

## 7. Rollback semantics

Bayrak off (`capabilities.serverSidePivoting=false`):

- Backend: `pivotMode=true` request → `501 pivot_not_supported`.
- Frontend: capability check ile pivot toggle disabled → user pivot mode'u açamaz.
- Rollback path: `reports/<key>.json` `capabilities.serverSidePivoting` `true` → `false` flip + image rebuild.

Silent flat fallback **yok** (Plan v2.1 §1 v3 revize iter-15 prensibi: "Flag off does NOT silently fall back to flat rows").

---

## 8. Risk / Trade-off Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Pivot cardinality patlama (1000+ kolon) | M | UX broken (grid hung) | `pivotDimMaxCardinality=50` cap; aşımda `400`. Custom rapor cap'i opt-in'le yükseltilebilir. |
| Weighted AVG `0/0` corner case | H | NULL döner, frontend NaN render risk | `NULLIF(...,0)` + frontend null-safe formatter (existing) |
| Discovery mode 2 query latency | M | P95 +200ms | Opt-in only; raporcular `static` default |
| Cross-schema weighted AVG denominator-losing pattern (e.g. `AVG(_branch_weighted_avg)`) | H | Audit-grade incorrectness | Positive IT (`buildWeightedAvgGroupedQuery_crossSchemaReturnsWeightedAverage`) + oracle-sanity IT (`crossSchemaWeightedAvg_fixtureWouldExposeAverageOfAveragesBias`) — fixture sensitivity ≥%5 |
| Frontend pivot UI toggle visible ama backend `501` | L | UX confusing | `serverSidePivoting=false` capability → toggle disabled (already handled) |

---

## 9. Definition of Done

- [ ] `SqlBuilder.buildPivotedGroupedQuery(...)` API yazılı + 5 unit test PASS
- [ ] `SqlBuilder` weighted-avg path 2 unit test PASS
- [ ] `SqlBuilderMssqlIntegrationTest` 5 yeni IT PASS (CI lane: `report-service-mssql-integration-test`)
- [ ] `POST /api/v1/reports/{key}/query` controller `pivotMode=true` + `valueCols[].aggFunc=weightedAvg` path entegre (501 → 200)
- [ ] `ReportCapabilities` envelope `serverSidePivoting=true` + `supportedAggFuncs=["sum","avg","min","max","count","weightedAvg"]`
- [ ] `reports/fin-muhasebe-detay.json` first-mover capability opt-in (Q1 default = `fin-muhasebe-detay only`)
- [ ] Frontend AG Grid pivot toggle gates on capability + supportedAggFuncs
- [ ] Vitest 3 yeni test PASS
- [ ] CHANGELOG.md (report-service) entry: "PR-0.4 pivot + weightedAvg"
- [ ] Plan v2.1 PR #75 commentary update (PR-0.4 status: implemented)
- [ ] Codex post-impl peer review AGREE (HARD RULE Cross-AI)
- [ ] CI 9/9 green + admin merge YASAK

---

## 10. Owner Karar Soruları (özet)

Bu spec'i okumak için 5 dakika ayırıp şu 7 soruya cevap verirseniz implementation başlayabilir:

1. **İlk pivot canary**: `fin-muhasebe-detay` only mu, capability-driven mı?
2. **Weighted AVG semantiği**: ANSI standart `SUM(v*w)/NULLIF(SUM(w),0)` mı, domain-specific denominator mu?
3. **Null/zero weight**: NULL döndür mü, sentinel bucket mı, drop mı?
4. **Multi-level + pivot beraber mi**: Hayır (PR-0.4: single-level + single pivot dim — Codex iter-4 önerisi), evet (PR-0.4'te zorunlu — kapsam genişler)?
5. **Export semantiği**: Pivot-applied flatten (önerilen), pivot-off only mı?
6. **Capability contract**: `serverSidePivoting` per-report flip (önerilen), her zaman açık mı?
7. **Acceptance gate**: 5 yeni IT test (önerilen), unit-only mı?

Default önerileri seçerseniz "AGREE → impl başla" cevabı yeterlidir.

---

## 11. Next Step

1. Owner UX feedback (Q1-Q7).
2. Codex iter-4 cross-AI peer review (spec sonrası, impl-time karar açılışı).
3. Sub-PR breakdown:
   - **PR-0.4a**: SqlBuilder single-level pivot path + safe alias metadata + bind-param CASE WHEN + unit tests
   - **PR-0.4b**: SqlBuilder weighted-avg path (raw UNION + outer aggregate, branch-level pre-agg YASAK) + unit tests
   - **PR-0.4c**: MSSQL Testcontainers IT (5 test, including positive cross-schema correctness + oracle-sanity bias guard)
   - **PR-0.4d**: Controller + capability flip + frontend AG Grid integration (canary: `fin-muhasebe-detay`)
   - **PR-0.4e** (follow-up): Multi-level row group + single pivot dim (sadece PR-0.4a..d kontratı yeşillendikten sonra)
4. Per-PR Codex AGREE → CI green → normal merge (admin merge YASAK).
