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
> **Status flag (capability envelope)**: Bu PR landed olduğunda
> `serverSidePivoting` capability flag fliplenecek. Şu an
> `false` (PR-0.1 envelope: `serverSideGrouping=true`,
> `serverSidePivoting=false`, `serverSideAggregation=true`).

---

## 0. TL;DR — Karar Götürülecek 7 Soru

| # | Soru | Default önerisi | Alternatif |
|---|---|---|---|
| 1 | İlk pivot capability hangi rapor(lar)da açılacak? | `fin-muhasebe-detay` only (tek rapor canary) | Capability-driven (her opt-in raporda otomatik) |
| 2 | Weighted AVG semantiği nedir? | `SUM(value * weight) / NULLIF(SUM(weight), 0)` (ANSI standart) | Domain-specific denominator (örn. `RECORD_COUNT` ya da `WORKING_DAYS`) |
| 3 | Null/zero weight bucket davranışı? | `NULL` döndür (NULLIF güvenli) | Sentinel bucket (`'no-weight'`) ya da bucket drop |
| 4 | Multi-level groupKeys + pivot beraber çalışacak mı? | Evet — single pivot dim + multi-level row groups | Single-level row group + single pivot dim (basitlik) |
| 5 | Export/count/pagination semantiği? | Pivot-applied result set üzerinde aynı pipeline (CSV/Excel için flatten) | Pivot off-mode export only (UX'te disable) |
| 6 | Capability contract: frontend pivot UI ne zaman açık olacak? | `capabilities.serverSidePivoting === true` olan raporlarda | Hep açık, server `501 pivot_not_supported` döner |
| 7 | Acceptance: yeni MSSQL Testcontainers IT? | Evet — `SqlBuilderMssqlIntegrationTest` 4 yeni test | Sadece unit test (PR-0.5 hardening yetersiz olur) |

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
- `YearlySchemaResolver` (Plan v2.1 §7) — UNION ALL across `workcube_mikrolink_<year>_<companyId>` schemas; cross-schema aggregation 'a hazır olmasına rağmen şu an AVG `AVG(AVG(x))` üretebilir (bug — PR-0.4 tarafından kapanır).
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

### 2.4 Request body (pivot path)

```json
POST /api/v1/reports/fin-muhasebe-detay/query
{
  "pivotMode": true,
  "rowGroupCols": [{"id": "ACCOUNT_CODE"}],
  "groupKeys": ["100.01.001"],
  "pivotCols": [{"id": "ACTION_TYPE"}],
  "valueCols": [{"id": "AMOUNT", "aggFunc": "sum"}],
  "filterModel": {},
  "sortModel": [],
  "startRow": 0,
  "endRow": 100
}
```

### 2.5 Response shape (pivot row)

```json
{
  "rows": [
    {
      "BRANCH_NAME": "İstanbul",
      "BORÇ_AMOUNT_sum": 12345.67,
      "ALACAK_AMOUNT_sum": 9876.54,
      "_rowCount": 42
    }
  ],
  "pivotResultFields": ["BORÇ_AMOUNT_sum", "ALACAK_AMOUNT_sum"]
}
```

- Pivot column kombinasyonu: `<pivotValue>_<valueColId>_<aggFunc>`.
- `pivotResultFields` AG Grid'in `setSecondaryColumns(...)` çağrısı için gerekli; frontend `gridApi.setSecondaryColumnDef(...)` ile dinamik kolonları çizer.

---

## 3. Backend (SqlBuilder pivot extension)

### 3.1 SqlBuilder API genişletme

```java
public BuiltQuery buildPivotedGroupedQuery(
    ReportDefinition def,
    YearlySchemaResolver.ResolvedSchemas resolvedSchemas,
    List<String> visibleColumns,
    String rowGroupCol,                  // single-level for now
    List<String> groupKeys,              // expanded path
    String pivotCol,                     // single pivot dim
    List<PivotValue> pivotValues,        // dynamically discovered
    List<GroupedAggregation> aggs,       // sum/avg/min/max/count
    Map<String, Object> filterModel,
    List<Map<String, String>> sortModel,
    String rlsWhere,
    Map<String, Object> rlsParams,
    Integer startRow, Integer endRow);
```

### 3.2 Generated SQL (single-level + single-pivot dim)

T-SQL pivot, native `PIVOT` keyword **kullanılmaz** (dinamik kolon listesi → CASE WHEN aggregation pattern).

```sql
SELECT
  BRANCH_NAME,
  SUM(CASE WHEN ACTION_TYPE = N'BORÇ'   THEN AMOUNT END) AS [BORÇ_AMOUNT_sum],
  SUM(CASE WHEN ACTION_TYPE = N'ALACAK' THEN AMOUNT END) AS [ALACAK_AMOUNT_sum],
  COUNT(*) AS _rowCount
FROM (
  -- sourceQuery (yearly UNION ALL)
) AS _src
WHERE _src.ACCOUNT_CODE = :groupKey0
GROUP BY BRANCH_NAME
ORDER BY BRANCH_NAME ASC
OFFSET :offset ROWS FETCH NEXT :limit ROWS ONLY;
```

### 3.3 Pivot value discovery

İki strateji opt-in (`reports/<key>.json` config):

| Strategy | Davranış | Trade-off |
|---|---|---|
| `static` | `pivotValues` listesi report config'inde sabit | Predictable; yeni değer için config update |
| `discovery` | İlk SELECT `DISTINCT pivotCol` ile değerleri çek, ikinci SELECT pivot CASE WHEN | 2 query (bir pre-flight + bir aggregation); cardinality cap (örn. 50) zorunlu |

Default: `static` (predictable). `discovery` yüksek-cardinality riskine sahip rapor için opsiyonel.

`discovery` mode'da cap aşımında `400 pivot_cardinality_exceeded` (cap configurable per-report).

### 3.4 Weighted AVG (cross-schema)

#### 3.4.1 Sorun

`UNION ALL` cross-schema query:

```sql
SELECT 100 AS amount FROM schema_a UNION ALL SELECT 200 FROM schema_b
```

Naive `AVG(amount)` per-schema → `(100 + 200) / 2 = 150`. **Doğru**.

Ama agent şu hatayı yapabilir:

```sql
SELECT AVG(per_year_avg) FROM (
  SELECT AVG(amount) AS per_year_avg FROM schema_a
  UNION ALL
  SELECT AVG(amount) AS per_year_avg FROM schema_b
) _u
```

Eğer `schema_a` 1 satır, `schema_b` 99 satır içeriyorsa: `per_year_avg` = `(100, 200)`, outer AVG = `150`. Doğru cevap: `(100*1 + 200*99) / 100 = 199.0` — **bug**.

#### 3.4.2 Düzeltme

Backend:
1. Her UNION ALL branch'ında `SUM(x)` + `COUNT(x)` carry et.
2. Outer aggregation `SUM(sum) / NULLIF(SUM(count), 0)` üzerinden çal.

```sql
SELECT BRANCH_NAME,
       SUM(_sum_amount) / NULLIF(SUM(_count_amount), 0) AS amount_avg,
       SUM(_count_amount) AS _rowCount
FROM (
  SELECT BRANCH_NAME, SUM(amount) AS _sum_amount, COUNT(amount) AS _count_amount
  FROM [schema_2025_35].[ACCOUNT_CARD_ROWS] WITH (NOLOCK)
  GROUP BY BRANCH_NAME
  UNION ALL
  SELECT BRANCH_NAME, SUM(amount) AS _sum_amount, COUNT(amount) AS _count_amount
  FROM [schema_2026_35].[ACCOUNT_CARD_ROWS] WITH (NOLOCK)
  GROUP BY BRANCH_NAME
) _u
GROUP BY BRANCH_NAME;
```

`SUM`, `MIN`, `MAX`, `COUNT` doğal compose: `SUM(SUM(x))`, `MIN(MIN(x))`, `MAX(MAX(x))`, `SUM(COUNT(x))`. Sadece `AVG` özel.

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

Generated SQL:

```sql
SUM(_sum_rate_x_weight) / NULLIF(SUM(_sum_weight), 0) AS RATE_weightedAvg
-- her UNION branch'ı:
SUM(RATE * WORKING_DAYS) AS _sum_rate_x_weight,
SUM(WORKING_DAYS) AS _sum_weight
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
| `buildPivotedGroupedQuery_singleLevelStaticPivotValues` | `SUM(CASE WHEN pivotCol = N'<value>' THEN val END)` × N |
| `buildPivotedGroupedQuery_dynamicPivotValuesAfterPreFlight` | İlk SELECT `DISTINCT pivotCol`, ikinci SELECT pivot |
| `buildPivotedGroupedQuery_rejectsMultiPivotDim` | `IllegalArgumentException` |
| `buildWeightedAvgGroupedQuery_carriesWeightSumAndProductSum` | `SUM(val * weight)` + `SUM(weight)` her UNION branch |
| `buildWeightedAvgGroupedQuery_outerAggDividesSumByWeightSum` | `SUM(_sum_x_weight) / NULLIF(SUM(_sum_weight), 0)` |

### 5.2 Integration (SqlBuilderMssqlIntegrationTest, MSSQL Testcontainers)

| Test | Senaryo |
|---|---|
| `buildPivotedGroupedQuery_overRealMssql_caseSumsCorrectly` | 2 pivot value × 3 row group bucket = 6 hücre matrix |
| `buildPivotedGroupedQuery_dynamicDiscoveryMatchesStaticOutput` | discovery mode == static mode equivalence (cardinality < cap) |
| `buildWeightedAvgGroupedQuery_singleSchemaParity` | weighted == naive avg (constant weight =1) |
| `buildWeightedAvgGroupedQuery_crossSchemaCorrectsBias` | `AVG(AVG(x))` ile `weightedAvg` farkını kanıtla (bias > %5) |
| `buildWeightedAvgGroupedQuery_zeroWeightProducesNull` | `NULLIF(SUM(weight),0)` denominator 0 → NULL döndür (Q3 default) |

PR #88'in `TEST_SCHEMA = "workcube_mikrolink_2026_35"` pattern'i devam eder; cross-schema test için ikinci schema ekle (`workcube_mikrolink_2025_35`).

### 5.3 Frontend (Vitest + React Testing Library)

| Test | Senaryo |
|---|---|
| `ReportGrid_pivotMode_disabledWhenCapabilityFalse` | `serverSidePivoting=false` → pivot toggle disabled |
| `ReportGrid_pivotMode_secondaryColumnsRendered` | `pivotResultFields` from response → `setSecondaryColumns` çağrılı |
| `ReportGrid_pivotMode_aggregateFunctionDropdownShowsWeightedAvg` | `supportedAggFuncs` include `weightedAvg` → dropdown'da gözük |

---

## 6. Out of scope (gelecek PR)

- **Multi-pivot dim** (örn. `MONTH × ACTION_TYPE` cross product)
- **Pivot + multi-level row group beraber**: PR-0.4 tek-level row group + tek pivot dim. Multi-level + pivot "PR-0.4b" extension'a girer.
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
| Cross-schema weighted AVG yanlış SUM(SUM)+SUM(COUNT) carry | H | Audit-grade incorrectness | IT test (`buildWeightedAvgGroupedQuery_crossSchemaCorrectsBias`) bias > %5 fail |
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
2. **Weighted AVG semantiği**: ANSI standart `SUM(v*w)/SUM(w)` mı, domain-specific denominator mu?
3. **Null/zero weight**: NULL döndür mü, sentinel bucket mı, drop mı?
4. **Multi-level + pivot**: Beraber mi (önerilen), tek-level mi?
5. **Export semantiği**: Pivot-applied flatten (önerilen), pivot-off only mı?
6. **Capability contract**: `serverSidePivoting` per-report flip (önerilen), her zaman açık mı?
7. **Acceptance gate**: 5 yeni IT test (önerilen), unit-only mı?

Default önerileri seçerseniz "AGREE → impl başla" cevabı yeterlidir.

---

## 11. Next Step

1. Owner UX feedback (Q1-Q7).
2. Codex iter-4 cross-AI peer review (spec sonrası, impl-time karar açılışı).
3. Sub-PR breakdown:
   - PR-0.4a: SqlBuilder pivot path + unit tests
   - PR-0.4b: SqlBuilder weighted-avg path + unit tests
   - PR-0.4c: MSSQL Testcontainers IT (5 test)
   - PR-0.4d: Controller + capability flip + frontend AG Grid integration
4. Per-PR Codex AGREE → CI green → normal merge (admin merge YASAK).
