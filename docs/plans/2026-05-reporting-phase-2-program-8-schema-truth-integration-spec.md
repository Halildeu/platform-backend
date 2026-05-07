# Phase 2 — Program 8: Schema Truth Integration (cross-cutting)

> **Status**: Spec draft — owner UX feedback bekliyor. Plan v2.1
> §3.8 detayını implementation-ready hale taşır. Programs 1 + 2'nin
> "schema source of truth" altyapısını tek yerde tanımlar.
>
> **Authors**: Claude (autonomous mode, 2026-05-07).
>
> **Cross-AI peer review**: Codex thread `019e0119-7c9d-7541-8059-
> f9553c3303ce` extended (post-PR-0.4 + Phase-2-Program-1 + 2
> consensus).
>
> **Related PRs**:
> - Plan v2.1 PR #75 §3.8 `Program 8 — Schema Truth Integration`
> - PR #91 Phase 2 Program 1 spec (build-time consumer)
> - PR #92 Phase 2 Program 2 spec (runtime consumer)
> - PR #90 PR-0.4 spec (frontend `useReportSchemaContext` consumer)

---

## 0. TL;DR — Karar Götürülecek 7 Soru

| # | Soru | Default önerisi | Alternatif |
|---|---|---|---|
| 1 | CI'da fresh snapshot pull cadence: pre-test always, daily, on-demand only? | **On-demand only** (snapshot file mtime stale-check yeterli; her CI run yeni pull bandwidth/rate limit yakar) | Pre-test always (zero-stale guarantee — bandwidth maliyetli) |
| 2 | Runtime Caffeine cache duration: 5-min, 15-min, 1-saat? | **5-min** (Plan §3.8 default; fast schema iteration için) | 15-min (lower upstream load); 1-saat (production stability ama dev iteration zorlaşır) |
| 3 | Tier 3 fallback (registry JSON column types): WARN'u nerede üret? | **Usage time WARN + metric increment** (her başvuruda) | Startup-time bulk validation WARN + zero usage metric |
| 4 | Committed snapshot age threshold: 30d, 14d, 7d? | **30 days** (Plan §3.8 default) | 14d (sıkı disiplin); 7d (CI noise riski yüksek) |
| 5 | `schema_truth_fallback_total` metric label cardinality | `{tier, schema_mode}` (cardinality kontrolü) | `{tier, report_key, schema_mode}` (granular debug, cardinality patlama riski) |
| 6 | FilterTranslator column type fetch ölçeği | **Per-request cache** (request-scope ColumnTypeRegistry; aynı request'te repeated lookups O(1)) | Eager session-load on JWT (preload); per-call (no cache, slow) |
| 7 | Frontend `useReportSchemaContext` davranışı | **On-mount + reactive on reportKey change** (PR-0.4 spec'le aligned) | On-mount only (stale on report switch); reactive on every render (over-fetch) |

---

## 1. Bağlam

### 1.1 Plan v2.1 referansı

- Plan §3.8 `Program 8 — Schema Truth Integration` (cross-cutting)
- Plan §3.1 `Program 1 — Report Contract Gate` (build-time consumer; PR #91)
- Plan §3.2 `Program 2 — Runtime Tenant Guard` (runtime consumer; PR #92)
- Plan §3.6 `Program 6 — Filter/Sort/Grouping Backend Correctness` (Plan v2.1 6b PR-0 → PR #79/#81/#82/#86/#88 merged)
- Plan §1 v3 revize: silent fallback yasak prensibi — Program 8'in 3-tier fallback'i her tier'da explicit signal verir (metric/WARN, sessiz drop YOK)

### 1.2 Halihazırda yerleşik altyapı

- `docs/migration/workcube-schema.json` — committed snapshot (3.4 MB, 1509 tablo, 26240 kolon, 1774 FK, 27 domain)
- `schema-service` `/api/v1/schema/snapshot` (port 8096) — runtime authoritative source
- `useReportSchemaContext` React hook (mevcut, mfe-reporting'de) — frontend filter type enrichment
- `report-service/src/main/resources/reports/<key>.json` `columns[].type` — Tier 3 fallback source

### 1.3 Bilinen sınırlamalar (Tier 3 fallback rationale)

- Schema-service unreachable + committed snapshot >30 gün eski + report registry JSON sadece column type bilgisi içeriyor
- Tier 3 fallback'in açılması "CI lifecycle dışı incident" sinyalidir → metric + WARN log critical
- Frontend `useReportSchemaContext` hook backend cevap yokken column type'ı registry'den çıkarmak zorunda kalabilir — bu durumda da WARN

---

## 2. Architecture

### 2.1 Java package layout (backend, Codex iter-1 §1+§3 absorb — `SchemaTruthLookupPolicy` enum)

```
report-service/src/main/java/com/example/report/schema/
  ├── SchemaTruthService.java              // Facade: 3-tier fallback orchestrator (policy-driven)
  ├── SchemaTruthLookupPolicy.java         // NEW enum (Codex iter-1 §1 absorb):
  │                                         //   BUILD_DETERMINISTIC, RUNTIME_STRICT_EXISTENCE, RUNTIME_DEGRADED_TYPE
  ├── SchemaTruthLookupContext.java        // NEW record (Codex iter-1 §3 absorb):
  │                                         //   { reportKey, schemaMode, policy, consumer }
  ├── SchemaSnapshot.java                   // POJO: { domain, tables: [{schema, name, columns: [{name, type}], fks }]}
  ├── tier/
  │   ├── SchemaServiceClient.java          // Tier 1: WebClient → /api/v1/schema/snapshot, 5-min Caffeine
  │   ├── CommittedSnapshotLoader.java       // Tier 2: docs/migration/workcube-schema.json mmap
  │   └── RegistryTypeFallback.java          // Tier 3: report registry columns[].type (report-scoped only)
  ├── observability/
  │   ├── SchemaTruthMetrics.java            // schema_truth_fallback_total{tier,schema_mode} +
  │   │                                      // schema_truth_lookup_total{schema_mode} +
  │   │                                      // schema_truth_cache_hit_total + snapshot_age_days
  │   └── SchemaTruthLogContext.java         // MDC enrichment (tier, schema_mode, report_key, age_days, consumer)
  └── consumer/
      ├── ColumnTypeRegistry.java            // public API: lookupColumnType(ctx, schema, table, column)
      ├── SchemaExistsService.java           // public API: exists(ctx, schemaName) — RUNTIME_STRICT_EXISTENCE only
      ├── TableColumnsListService.java       // public API: listColumns(ctx, schema, table)
      └── RequestColumnTypeCache.java        // @RequestScope (Codex iter-1 §5 absorb) — request-scope cache,
                                             //   no ThreadLocal Caffeine; ObjectProvider for non-web context
```

### 2.1.1 `SchemaTruthLookupPolicy` (Codex iter-1 §1 absorb)

```java
public enum SchemaTruthLookupPolicy {
    /**
     * Build-time deterministic mode (CI mvn test).
     * Tier order: 2 (committed snapshot) → 3 (registry types).
     * Tier 1 (schema-service) DISABLED — network-independent CI.
     * Used by: Phase 2 Program 1 ContractValidator (PR #91).
     */
    BUILD_DETERMINISTIC,

    /**
     * Runtime strict existence check (production).
     * Tier order: 1 (schema-service Caffeine) ONLY.
     * Tier 1 miss / unreachable → 503 schema_resolver_miss (NO fallback).
     * Used by: Phase 2 Program 2 TenantBoundaryGuard.exists(schema) (PR #92).
     */
    RUNTIME_STRICT_EXISTENCE,

    /**
     * Runtime degraded type lookup (production fast path).
     * Tier order: 1 (Caffeine) → 2 (committed snapshot) → 3 (registry types).
     * All 3 tiers allowed; Tier 3 = WARN + X-Schema-Truth-Tier header.
     * Used by: FilterTranslator + SqlBuilder + frontend useReportSchemaContext.
     */
    RUNTIME_DEGRADED_TYPE
}
```

Bu enum runtime'daki **iki farklı semantik**i (PR #92 absorb strict vs Plan §3.8 degraded) policy-level ayırır → aynı `SchemaTruthService` çağrısının iki güvenlik semantiği üretmesi önlenir.

### 2.1.2 Capability Matrix (Codex iter-1 §2 absorb)

Tier 3 registry types kaynağı yalnızca **report-scoped column type fallback** verebilir; full DB-level capability'leri karşılayamaz:

| Consumer API | Tier 1 | Tier 2 | Tier 3 | Policy |
|---|---|---|---|---|
| `lookupColumnType(reportKey, field)` (report-scoped) | ✓ | ✓ | ✓ (report-scoped column types) | RUNTIME_DEGRADED_TYPE / BUILD_DETERMINISTIC |
| `lookupColumnType(schema, table, column)` (DB-level) | ✓ | ✓ | ✗ (registry'de schema-table-column mapping yok) | RUNTIME_DEGRADED_TYPE / BUILD_DETERMINISTIC |
| `exists(schemaName)` | ✓ | ✗ (build-time deterministic OK; runtime fail-closed strict) | ✗ | RUNTIME_STRICT_EXISTENCE only |
| `listColumns(schema, table)` (full table scan) | ✓ | ✓ | report-scoped visible columns only (NOT DB truth — partial result + WARN) | RUNTIME_DEGRADED_TYPE / BUILD_DETERMINISTIC |

Tier 3 capability'si "DB-level absolute truth" değil → consumer'lar bu sınırı bilerek Tier 3 sonucunu kullanır (örn. PR-0.4 discovery mode pivot value list partial olabilir + WARN).

### 2.2 3-Tier Fallback Chain (Plan §3.8 default)

```
Request: lookupColumnType(schema="workcube_mikrolink_2026_35", table="ACCOUNT_CARD_ROWS", column="AMOUNT")
  ↓
Tier 1: SchemaServiceClient.lookup(...)
  ├─ Cache hit (Caffeine 5-min, Codex iter-1 yet to set TTL): return cached
  └─ Cache miss:
      ├─ schema-service GET /api/v1/schema/snapshot/{schema}/{table}
      ├─ HTTP 200 → cache + return
      ├─ HTTP timeout / 5xx → metric `schema_truth_fallback_total{tier="schema_service"}` + Tier 2'e düş
      └─ HTTP 404 → no fallback (column gerçekten yok); throw `SchemaTruthMissException` (RC-004 tarafından handle edilir)
  ↓ (Tier 1 fail-soft case)
Tier 2: CommittedSnapshotLoader.lookup(...)
  ├─ Snapshot mmap'inde: return (slower than Tier 1 cache, faster than Tier 3)
  ├─ Snapshot age check: if mtime > 30d → metric `schema_truth_snapshot_age_warn`
  ├─ Schema/table/column found → metric `schema_truth_fallback_total{tier="committed_snapshot"}`
  └─ Schema/table/column NOT found → Tier 3'e düş (eski snapshot'ta yeni schema yok)
  ↓ (Tier 2 fail-soft case — yeni schema/column committed snapshot'tan önce)
Tier 3: RegistryTypeFallback.lookup(...)
  ├─ Report registry JSON `<key>.json` `columns[].type` çıkar (rapor-bazlı, schema-table-column tracking eksik)
  ├─ Found → metric `schema_truth_fallback_total{tier="registry_type"}` + WARN log
  └─ Not found → throw `SchemaTruthMissException` (Tier 3 ulaşılır ama miss = gerçek incident)
```

### 2.3 Lookup Policy → Tier Behavior (Codex iter-1 §1 absorb)

Aynı `SchemaTruthService` çağrısı **policy enum**'a göre farklı tier davranışı üretir:

| Policy | Tier 1 | Tier 2 | Tier 3 |
|---|---|---|---|
| **`BUILD_DETERMINISTIC`** (Phase 2 Program 1 PR #91) | **DISABLED** (Q1 default: on-demand only; CI bandwidth/rate limit önle) | **PRIMARY** (committed snapshot) | Fallback (RC-004 kontrolü için, capability matrix Tier 3 izinli case'lerde) |
| **`RUNTIME_STRICT_EXISTENCE`** (Phase 2 Program 2 PR #92 `TenantBoundaryGuard.exists`) | **PRIMARY** (5-min Caffeine cache) | **DISABLED** (no fallback — runtime fail-closed) | **DISABLED** (no fallback) |
| **`RUNTIME_DEGRADED_TYPE`** (FilterTranslator + SqlBuilder + `useReportSchemaContext`) | **PRIMARY** (5-min Caffeine cache) | Fallback if Tier 1 fail-soft + WARN log | Fallback if Tier 2 fail-soft + WARN + `X-Schema-Truth-Tier: registry_type` header |

Build-time'da Tier 1 disable: deterministic CI output (network-independent); committed snapshot zaten primary kaynak.

Runtime'da policy-driven:
- `RUNTIME_STRICT_EXISTENCE`: fail-closed disiplini PR #92 absorb parity (silent fallback YOK; Plan §1 v3 revize prensibi)
- `RUNTIME_DEGRADED_TYPE`: fast path + 3-tier fallback (column type lookup için degraded OK; tier sinyali frontend transparent)

### 2.4 Metric semantik (Q5 default `{tier, schema_mode}`)

```promql
# Tier breakdown (kim hangi tier'a düşüyor)
sum by (tier, schema_mode) (rate(schema_truth_fallback_total[5m]))

# Cache hit oranı (Tier 1 health)
sum(rate(schema_truth_cache_hit_total[5m]))
  /
sum(rate(schema_truth_lookup_total[5m]))

# Snapshot age trend (committed snapshot tazelik)
schema_truth_snapshot_age_days

# Snapshot age threshold breach (Q4 default 30 days)
schema_truth_snapshot_age_warn  # binary 0/1
```

### 2.5 Frontend integration (`useReportSchemaContext` — Codex iter-1 §4 absorb header canonical)

```ts
// mfe-reporting/src/hooks/useReportSchemaContext.ts
export function useReportSchemaContext(reportKey: string) {
  const { data, isLoading, error } = useQuery({
    queryKey: ["schema-context", reportKey],   // reactive on reportKey change (Q7 default)
    queryFn: async () => {
      const response = await fetch(`/api/v1/reports/${reportKey}/schema-context`);
      // Canonical tier signal: HTTP response header (Codex iter-1 §4 absorb)
      const tier = response.headers.get("X-Schema-Truth-Tier");
      const body = await response.json();
      if (tier === "registry_type") {
        console.warn(
          `[schema-truth] Report '${reportKey}' served from Tier 3 registry types — schema-service + committed snapshot both fail-soft`
        );
      }
      return { ...body, tier };
    },
    staleTime: 5 * 60 * 1000,                   // 5-min mirror of backend Caffeine
    refetchOnMount: true,                        // on-mount fetch (Q7 default)
  });
  return { columnTypes: data?.columnTypes, tier: data?.tier, isLoading, error };
}
```

Tier signal **canonical = HTTP response header** `X-Schema-Truth-Tier` (`schema_service` | `committed_snapshot` | `registry_type`); body'de opsiyonel echo olarak `tier` field'ı eklenir ama hook'un karar yüzeyi header. Bu yaklaşım test plan + DoD + failure semantics ile aligned (önceki body-only çelişkisi kapandı).

---

## 3. Consumer Integration Contracts

### 3.1 Phase 2 Program 1 (build-time validator) — PR #91

`ContractValidator` consumer:
- `RC-004` (rowFilter column allowlisted): `ColumnTypeRegistry.exists(schema, table, column)` → committed snapshot primary
- `RC-006` (`mode=none` reports cannot reference tenant fact tables): `SchemaExistsService.isTenantFactTable(schema, table)` → committed snapshot scan
- `RC-008` (schemaResolver registered): no Schema Truth dependency (config validation only)

CI integration: `mvn test` Tier 1 disabled; Tier 2 (committed snapshot) primary.

### 3.2 Phase 2 Program 2 (runtime tenant guard) — PR #92

`TenantBoundaryGuard` consumer:
- `SchemaExistsService.exists(schemaName)` → Tier 1 (Caffeine cache) primary
- Tier 1 fail-soft → Tier 2 fallback'a düşmez; runtime'da fail-closed `503 schema_resolver_miss` (Codex iter-2 absorb)

> Runtime contract'ı build-time'dan farklı: build-time'da Tier 2 graceful, runtime'da Tier 1 strict — Plan §3.8 v3 revize prensibi.

### 3.3 PR-0.4 (pivot + weighted AVG) — PR #90

`SqlBuilder` consumer:
- Pivot value discovery: `TableColumnsListService.listColumns(schema, table)` (discovery mode) — Tier 1 primary
- Weighted AVG numerator/denominator: column type-aware (DECIMAL precision contract) — `ColumnTypeRegistry.lookupColumnType(...)` Tier 1 primary

Frontend `useReportSchemaContext`: AG Grid colDef enrichment (filter type, value formatter) — Tier 1 primary; Tier 3 transparent warning to dev console.

### 3.4 FilterTranslator (existing — Plan v2.1 §3.6 PR-0 zinciri)

`FilterTranslator` runtime'da column type-aware T-SQL üretir (text → LIKE; number → equals/range; date → BETWEEN). `ColumnTypeRegistry.lookupColumnType(...)` policy=`RUNTIME_DEGRADED_TYPE` (Tier 1 primary, Tier 2/3 fallback OK).

Per-request cache (Q6 default — Codex iter-1 §5 absorb): **Spring `@RequestScope` bean** (`RequestColumnTypeCache`) + `ObjectProvider<RequestColumnTypeCache>` for non-web context. ThreadLocal Caffeine LayerCache **kullanılmaz** (Tier 1 zaten 5-min Caffeine cache; ikinci layer overkill + ThreadLocal leak riski). Basit `Map<LookupKey, SchemaTruthResult>` request-scope bean'in iç state'i; servlet API `FilterTranslator`'a sızmaz.

```java
@Component
@RequestScope
public class RequestColumnTypeCache {
    private final Map<LookupKey, SchemaTruthResult> cache = new HashMap<>();
    public SchemaTruthResult getOrCompute(LookupKey key, Supplier<SchemaTruthResult> compute) { ... }
}

// FilterTranslator (no servlet dependency)
@Component
public class FilterTranslator {
    private final ObjectProvider<RequestColumnTypeCache> cacheProvider;
    private final ColumnTypeRegistry registry;
    public String translate(...) {
        RequestColumnTypeCache cache = cacheProvider.getIfAvailable(() -> null);
        if (cache != null) {
            return cache.getOrCompute(...);  // request-scope hot path
        }
        // non-web context (e.g. unit test, scheduled job): direct registry call
        return registry.lookupColumnType(...);
    }
}
```

`@RequestScope` Spring lifecycle clean (request done → bean GC); ThreadLocal cleanup endişesi yok.

---

## 4. Failure semantics

| Status | Code | When | Action |
|---|---|---|---|
| Build-time `400` | `report_contract_violation` (RC-004) | Tier 2 committed snapshot'ta column yok + Tier 3 registry'de de yok | Validator FAIL; spec'te tanımlı RC-004 path |
| Runtime `503` | `schema_resolver_miss` | Tier 1 Caffeine miss + schema-service unreachable | Plan §3.8 + PR #92 absorb: runtime fail-closed |
| Runtime `200` (degraded) | header `X-Schema-Truth-Tier: registry_type` + WARN log | Tier 1 + 2 fail-soft, Tier 3 fallback success | Frontend `console.warn`; ops alert (`schema_truth_fallback_total{tier="registry_type"}` artarsa pager) |
| Build-time WARN | `report_contract_snapshot_age_warn` summary counter | snapshot mtime > 30d | CI doesn't fail; refresh runbook reference |
| Runtime WARN | `schema_truth_cache_miss_burst` | 5-min cache miss rate > %50 (load test or schema-service degraded) | Health check; Caffeine TTL re-evaluation |

---

## 5. Test plan

### 5.1 Unit (per component)

| Test | Senaryo |
|---|---|
| `SchemaServiceClient_cacheHitReturnsCached` | İlk çağrı service → cache; ikinci çağrı cache hit |
| `SchemaServiceClient_cacheMissAndServiceTimeoutFallsToTier2` | Mock 5xx → CommittedSnapshotLoader çağrılı |
| `SchemaServiceClient_404DoesNotFallback` | Mock 404 → SchemaTruthMissException (column gerçekten yok) |
| `CommittedSnapshotLoader_lookupAccountCardRowsAmount` | workcube-schema.json içinde ACCOUNT_CARD_ROWS.AMOUNT type=DECIMAL(18,2) |
| `CommittedSnapshotLoader_unknownSchemaTable_throwsMiss` | yeni table committed snapshot'ta yok → Tier 3'e düş |
| `RegistryTypeFallback_extractsFromReportColumnsArray` | `<key>.json columns[]` parse + lookup |
| `SchemaTruthService_3tier_metricsIncrement` | Tier 1 fail-soft → Tier 2 hit → metric `tier="committed_snapshot"` increment |
| `SchemaTruthService_snapshotAgeWarn_at31Days` | `Clock` fixed to 2026-05-07 + snapshot mtime 2026-04-06 → warn metric |

### 5.2 Integration (Spring `@SpringBootTest` + WireMock for schema-service)

| Test | Senaryo |
|---|---|
| `SchemaTruthEndToEnd_TenantBoundaryGuardConsumesService` | TenantBoundaryGuard preflight → SchemaExistsService → WireMock schema-service → 200 OK |
| `SchemaTruthEndToEnd_serviceUnreachableThrows503` | WireMock unavailable → SchemaExistsService Tier 1 fail-soft → runtime fail-closed 503 (PR #92 contract parity) |
| `SchemaTruthEndToEnd_filterTranslatorUsesColumnType` | FilterTranslator `<col>=value` → column type lookup → AMOUNT DECIMAL → numeric WHERE clause T-SQL |

### 5.3 Frontend (Vitest)

| Test | Senaryo |
|---|---|
| `useReportSchemaContext_fetchesOnMount` | hook mount → `/api/v1/reports/{key}/schema-context` GET |
| `useReportSchemaContext_refetchesOnReportKeyChange` | reportKey "fin-muhasebe-detay" → "stok-hareket" → ikinci fetch |
| `useReportSchemaContext_consoleWarnsOnTier3` | response header `X-Schema-Truth-Tier: registry_type` → console.warn |

---

## 6. Out of scope (gelecek PR / Phase başka programlar)

- **Schema-service kendi snapshot pull strategy**: Program 8 consumer; schema-service'in tablo discovery iç işleyişi ayrı (faz 16.2)
- **Multi-table composition governance** — Phase 2 Program 7 (separate spec)
- **Action menu standard** — Phase 2 Program 3 (frontend, mfe-reporting)
- **Yearly schema parametric crawl** (Faz 16.2.P) — defer, kullanıcı kararı bekliyor
- **Non-Workcube source adapters** — Plan v3+ scope

---

## 7. Rollback semantics

3-tier fallback orchestrator merge edilirse + 1 mevcut consumer (FilterTranslator, TenantBoundaryGuard, vb.) yanlış column type alırsa:
1. Tier 1 cache TTL kısaltılır (5-min → 1-min) → consumer'a tazelenmiş veri akar
2. Tier 2 committed snapshot refresh: `docs/migration/workcube-schema.json` yenilenir + commit
3. Tier 3 registry types `<key>.json` correction commit

Rollback path: feature flag `reporting.schema-truth.tier-1-enabled=false` → Tier 1 disabled → Tier 2 primary (build-time mode'a düşer); production runtime'da fail-closed 503 yerine committed snapshot fallback'i açılır (geçici, debug için).

Plan §1 v3 revize: silent fallback yasak — feature flag explicit + WARN log + metric.

---

## 8. Risk / Trade-off Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Tier 1 schema-service rate limiting (5-min cache miss burst) | M | Latency p95 artış + 503 spike | `schema_truth_cache_miss_burst` WARN; Caffeine TTL adaptive (load > threshold → TTL extend) |
| Committed snapshot stale (>30d) → Tier 2 yanlış column type | M | RC-004 yanlış FAIL/WARN; runtime FilterTranslator yanlış WHERE clause | `schema_truth_snapshot_age_warn` + refresh runbook (`docs/runbooks/refresh-schema-snapshot.md`) |
| Tier 3 fallback chronic kullanım | H | Sistem genelinde "registry types" tier'ında kalmak = Tier 1 + 2 sürekli fail-soft = bilinçsiz incident | Pager alert: `schema_truth_fallback_total{tier="registry_type"} > 0` 5-min content over threshold |
| `ColumnTypeRegistry.lookupColumnType` per-request cache thread leak | L | ThreadLocal residual | Plan §3.2 PR #92 ThreadLocal cleanup pattern (afterCompletion clear) |
| Frontend `useReportSchemaContext` 5-min stale + report definition update | L | Stale colDef enrichment | `staleTime: 5 * 60 * 1000` + `refetchOnMount: true` (Q7 default) |

---

## 9. Definition of Done

- [ ] `SchemaTruthService` facade + 3-tier fallback orchestrator
- [ ] `SchemaServiceClient` Tier 1 + 5-min Caffeine cache
- [ ] `CommittedSnapshotLoader` Tier 2 + snapshot age check
- [ ] `RegistryTypeFallback` Tier 3 + WARN at usage time (Q3 default)
- [ ] `ColumnTypeRegistry` + `SchemaExistsService` + `TableColumnsListService` consumer interfaces
- [ ] `SchemaTruthLookupContext` record (`reportKey`, `schemaMode`, `policy`, `consumer`) — API'a context parameter olarak inject (Codex iter-1 §3 absorb)
- [ ] `SchemaTruthMetrics` (6 metric: `schema_truth_lookup_total{schema_mode}` + `schema_truth_fallback_total{tier,schema_mode}` + `schema_truth_cache_hit_total` + `schema_truth_snapshot_age_days` + `schema_truth_snapshot_age_warn` + `schema_truth_cache_miss_burst`)
- [ ] `SchemaTruthLogContext` MDC enrichment (`tier`, `schema_mode`, `age_days`)
- [ ] Per-request cache (Q6 default — Codex iter-1 §5 absorb) — Spring `@RequestScope` `RequestColumnTypeCache` bean + `ObjectProvider<RequestColumnTypeCache>` for non-web context (no ThreadLocal Caffeine, simple Map)
- [ ] Frontend `useReportSchemaContext` — on-mount + reactive on reportKey change (Q7 default; 5-min staleTime mirror of backend cache)
- [ ] `GET /api/v1/reports/{key}/schema-context` endpoint + `X-Schema-Truth-Tier` response header
- [ ] 8 unit test PASS + 3 SpringBoot IT PASS + 3 Vitest test PASS
- [ ] Phase 2 Program 1 (PR #91) `ContractValidator` consumer integration
- [ ] Phase 2 Program 2 (PR #92) `TenantBoundaryGuard` consumer integration
- [ ] PR-0.4 (PR #90) `SqlBuilder` discovery + weighted AVG consumer integration
- [ ] FilterTranslator (existing) consumer integration (Plan v2.1 §3.6)
- [ ] ADR `docs/adr/0008-schema-truth-integration.md` (Plan v2.1 §3.8 mandate)
- [ ] Codex post-impl peer review AGREE (HARD RULE Cross-AI)
- [ ] CI 9/9 green + admin merge YASAK

---

## 10. Owner Karar Soruları (özet)

Bu spec'i okumak için 5 dakika ayırıp şu 7 soruya cevap verirseniz implementation başlayabilir:

1. **CI snapshot pull cadence**: on-demand only (önerilen) mi, pre-test always mı?
2. **Cache duration**: 5-min (önerilen) mi, 15-min mi, 1-saat mi?
3. **Tier 3 WARN scope**: usage time (önerilen) mi, startup-time bulk mı?
4. **Snapshot age threshold**: 30d (önerilen) mu, 14d/7d mi?
5. **`schema_truth_fallback_total` cardinality**: `{tier, schema_mode}` (önerilen) mu, granular mı?
6. **FilterTranslator column type fetch**: per-request cache (önerilen) mi, eager session mi?
7. **Frontend `useReportSchemaContext`**: on-mount + reactive (önerilen) mi, on-mount only mi?

Default önerileri seçerseniz "AGREE → impl başla" cevabı yeterlidir.

---

## 11. Sub-PR breakdown

1. **Phase-2-Program-8a**: `SchemaTruthService` facade + Tier 1 (`SchemaServiceClient` + 5-min Caffeine) + `SchemaSnapshot` POJO + 3 unit test
2. **Phase-2-Program-8b**: Tier 2 (`CommittedSnapshotLoader`) + Tier 3 (`RegistryTypeFallback`) + 5 unit test (3 + 2)
3. **Phase-2-Program-8c**: Consumer interfaces (`ColumnTypeRegistry` + `SchemaExistsService` + `TableColumnsListService`) + per-request cache wiring
4. **Phase-2-Program-8d**: `SchemaTruthMetrics` + `SchemaTruthLogContext` MDC + 3 SpringBoot IT
5. **Phase-2-Program-8e**: Frontend `useReportSchemaContext` + `GET /schema-context` endpoint + `X-Schema-Truth-Tier` response header + 3 Vitest test + ADR-0008

5 sub-PR, her biri bağımsız Codex iter cycle, normal merge (admin merge YASAK), sırasıyla.

---

## 12. Plan v2.1 referans çapraz-link

- §3.8 `Program 8 — Schema Truth Integration` — bu spec
- §3.1 `Program 1` (PR #91) — build-time consumer
- §3.2 `Program 2` (PR #92) — runtime consumer
- §3.6 `Program 6 — Filter/Sort/Grouping Backend Correctness` — FilterTranslator runtime consumer (PR-0 chain merged)
- PR-0.4 (PR #90) — discovery mode + weighted AVG consumer

---

## 13. Next Step

1. Owner UX feedback (Q1-Q7).
2. Codex iter-N cross-AI peer review (spec sonrası, impl-time karar açılışı).
3. Sub-PR breakdown'a göre Phase-2-Program-8a impl başlar (Phase 2 Program 1 + 2 spec'leriyle paralel — Program 8 onlar için altyapı sağlar; ideal sıra: 8a/b/c önce, sonra Program 1+2 impl başlar).
4. Per-PR Codex AGREE → CI green → normal merge.
