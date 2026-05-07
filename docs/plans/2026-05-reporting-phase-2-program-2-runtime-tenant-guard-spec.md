# Phase 2 — Program 2: Runtime Tenant Guard

> **Status**: Spec draft — owner UX feedback bekliyor. Plan v2.1
> §3.2 detayını implementation-ready hale taşır.
>
> **Authors**: Claude (autonomous mode, 2026-05-07).
>
> **Cross-AI peer review**: Codex thread `019e0119-7c9d-7541-8059-
> f9553c3303ce` extended (post-PR-0.4 + Phase-2-Program-1 consensus).
>
> **Related PRs**:
> - Plan v2.1 PR #75 (`docs/plans/2026-05-reporting-platform-hardening.md`) §3.2
> - Phase 2 Program 1 spec PR #91 (build-time validator side; this PR is runtime side)
> - PR-0.4 spec PR #90 (pattern reference — 9 iter Codex AGREE)
> - Plan §3.8 Schema Truth Integration (cross-cutting; bu spec'in `schema-service` çağrısı altyapısını tanımlar)

---

## 0. TL;DR — Karar Götürülecek 7 Soru

| # | Soru | Default önerisi | Alternatif |
|---|---|---|---|
| 1 | Super-admin'a tenant-bound rapor request'inde `X-Selected-Company-Id` header eksikse: 400 fail-closed mu, yoksa "select company" UI'a yönlendir mi (303 See Other)? | **400 `tenant_selection_required`** + frontend toast (Plan §3.2 default) | 303 + Location header (UX-friendly ama caching/redirect chain riski) |
| 2 | Single-company user'lar için `X-Selected-Company-Id` otomatik mi, yoksa client-set zorunlu mu? | Otomatik (single tenant scope: 1 company → server resolver auto-pick) | Client-set zorunlu (homogeneous behavior) |
| 3 | `extractCompanyFromSchema()` legacy exception ne kadar geniş? | Sadece `schemaMode=static` raporlar (RC-003 exempt eder) — non-static raporlarda forbidden | Geniş (her rapor type'da fallback olarak çalışsın) |
| 4 | Schema existence check (Plan §3.8 §4.2 fallback): cache miss + service unreachable → behavior? | 503 + `report_resolved_schema_miss` metric (Plan §3.2 explicit) | Stale snapshot'a düş + WARN (graceful degrade) |
| 5 | TenantBoundaryGuard preflight ordering: filter chain'in neresinde? | Spring Security filter chain'inden sonra, controller invocation'dan önce (`HandlerInterceptor`) — RBAC/scope check'i sonra | Controller advice (`@ControllerAdvice`) — slower but more localized |
| 6 | `report_resolved_schema_miss` metric label cardinality: per-tenant + per-report? | `{report_key, schema_mode}` only — tenant_id label cardinality patlamasını önler | `{report_key, tenant_id, schema_mode}` (granular debug) |
| 7 | Acceptance: integration test sayısı? | 4 yeni Spring `@WebMvcTest` IT (current spec'te tanımlı senaryolar) | Sadece unit + 1 e2e Testcontainers IT |

---

## 1. Bağlam

### 1.1 Plan v2.1 referansı

- Plan §3.2 `Program 2 — Runtime Tenant Guard`
- Plan §3.1 `Program 1 — Report Contract Gate (build-time)` — Program 1'in `tenantBoundary.mode` field'ı bu spec'in girdisi
- Plan §3.8 `Program 8 — Schema Truth Integration` — schema-service `schemaExists()` çağrısı + 3-tier fallback
- Plan §3.6 `Program 6 — Filter/Sort/Grouping Backend Correctness` — `YearlySchemaResolver` halihazırda var; Program 2 onu hardening eder

### 1.2 Halihazırda yerleşik altyapı

- `YearlySchemaResolver` — `workcube_mikrolink_{year}_{companyId}` resolver (legacy fallback'leri hâlâ açık)
- `def.sourceSchema()` — ReportDefinition'da static schema field; `tenantBoundary.mode=schema` raporlar için artık fallback OLMAYACAK
- PR #70 — selected company outside user scope → 403 (existing semantics)
- Spring Security filter chain — RBAC/scope evaluation (mevcut)
- `schema-service` `/api/v1/schema/snapshot` — runtime schema source

### 1.3 Bilinen riskler (Plan §3.2 Outcome rationale)

- Build-time validator (Program 1) yeni rapor merge'lerinde gate yapar; **mevcut çalışan rapor'larda runtime tenant boundary breach** hâlâ mümkün:
  - Bug: super-admin (`scopeType=ALL`) `X-Selected-Company-Id` header eksik → `extractCompanyFromSchema()` legacy fallback 1. company'ye düşüyor → cross-tenant data leak
  - Bug: yarım rebuild edilmiş image'da static `def.sourceSchema()` falback → multi-tenant rapor tek company'ye drop oluyor
- Bu spec runtime path'inde fail-closed gate koyarak bu sınıf bug'ları kapatır

---

## 2. Architecture

### 2.1 Java package layout

```
report-service/src/main/java/com/example/report/runtime/
  ├── tenant/
  │   ├── TenantBoundaryGuard.java        // HandlerInterceptor — preflight component
  │   ├── TenantContext.java               // ThreadLocal {selectedCompanyId, scope, userId}
  │   ├── TenantHeaderExtractor.java       // X-Selected-Company-Id → Long (validates UUID format)
  │   └── TenantSelectionException.java    // 400 / 403 / 503 hierarchies
  ├── schema/
  │   ├── CurrentTenantSchemaResolver.java // NEW: workcube_mikrolink_{companyId}
  │   ├── YearlySchemaResolver.java        // hardening (no def.sourceSchema() fallback for mode=schema)
  │   ├── CanonicalSchemaResolver.java     // workcube_mikrolink (no-op for tenantBoundary)
  │   └── SchemaResolverFactory.java       // tenantBoundary.schemaResolver → resolver instance
  └── observability/
      ├── ReportRuntimeMetrics.java         // report_resolved_schema_miss + schema_truth_fallback_total
      └── TenantGuardLogContext.java        // MDC enrichment (selectedCompanyId, schemaMode)
```

### 2.2 Filter chain integration

```
Spring Security filter chain
  ↓
JwtAuthenticationFilter (existing) — sets SecurityContext.userId, scope
  ↓
[NEW] TenantBoundaryGuard (HandlerInterceptor preHandle)
  ↓ (per-request preflight)
  - resolveDef(reportKey)
  - if def.tenantBoundary.mode == "schema":
    - companyId = TenantHeaderExtractor.extract(request)
    - if companyId == null:
      - if scope == ALL (super-admin): throw 400 tenant_selection_required
      - if scope == COMPANY + user has 1 company: auto-pick
      - if scope == COMPANY + user has N>1 companies: throw 400 tenant_selection_required
    - schema = SchemaResolverFactory.resolve(def, companyId, year)
    - if !schemaService.schemaExists(schema): throw 503 + metric
    - TenantContext.set(companyId, scope, userId)
  - if def.tenantBoundary.mode == "row":
    - same logic + add rowFilter to sql params
  - if def.tenantBoundary.mode == "none":
    - no-op (master/lookup raporlar)
  ↓
Controller invocation (already RBAC-checked)
  ↓
SqlBuilder uses TenantContext.get() to bind params
  ↓
ReportingResult flushed
  ↓
[NEW] TenantBoundaryGuard.postHandle: TenantContext.clear()
```

### 2.3 `TenantHeaderExtractor` semantics

```java
public class TenantHeaderExtractor {
    public static final String HEADER = "X-Selected-Company-Id";

    public static Optional<Long> extract(HttpServletRequest request) {
        String value = request.getHeader(HEADER);
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(Long.parseLong(value));
        } catch (NumberFormatException e) {
            throw new TenantSelectionException(
                "tenant_selection_invalid",
                "X-Selected-Company-Id must be a numeric ID",
                400);
        }
    }
}
```

### 2.4 `CurrentTenantSchemaResolver` (NEW)

```java
@Component
public class CurrentTenantSchemaResolver implements SchemaResolver {
    @Override
    public List<String> resolve(ReportDefinition def, TenantContext ctx) {
        Long companyId = ctx.selectedCompanyId();
        if (companyId == null) {
            throw new TenantSelectionException(
                "tenant_selection_required",
                "Report '" + def.key() + "' requires X-Selected-Company-Id",
                400);
        }
        return List.of("workcube_mikrolink_" + companyId);
    }

    @Override
    public String resolverId() { return "workcube-current-company"; }
}
```

### 2.5 `YearlySchemaResolver` hardening

```java
public class YearlySchemaResolver implements SchemaResolver {
    @Override
    public List<String> resolve(ReportDefinition def, TenantContext ctx) {
        // OLD (deleted): if (companyId == null) return List.of(def.sourceSchema());
        // NEW: fail-closed for tenantBoundary.mode=schema
        if (def.tenantBoundary().mode().equals("schema")) {
            Long companyId = ctx.selectedCompanyId();
            if (companyId == null) {
                throw new TenantSelectionException(
                    "tenant_selection_required",
                    "Report '" + def.key() + "' (schema mode) requires X-Selected-Company-Id",
                    400);
            }
            // ... existing yearly + companyId composition
        }

        // mode=static raporlar için legacy extractCompanyFromSchema() exception
        if (def.tenantBoundary().mode().equals("static")) {
            return List.of(def.sourceSchema());
        }

        // mode=none / mode=row için existing logic
        // ...
    }
}
```

`extractCompanyFromSchema()` artık sadece `schemaMode=static` raporlar için (RC-003 Tier 1+ WARN scope'unda — Plan §3.1 expected migration path).

---

## 3. Failure semantics

| Status | Code | When | Header/Frontend behavior |
|---|---|---|---|
| `400` | `tenant_selection_required` | Super-admin or multi-company user without `X-Selected-Company-Id` on `mode=schema/row` rapor | Frontend modal: "Şirket seçimi yapın" — server-driven (no silent fallback) |
| `400` | `tenant_selection_invalid` | Header `X-Selected-Company-Id` non-numeric | Same as above + invalid format error |
| `400` | `tenant_boundary_mismatch` | `mode=none` rapor + `X-Selected-Company-Id` header set (boundary clarity) | Header should not have been sent |
| `403` | `tenant_scope_violation` | Selected company outside user's scope (existing PR #70 semantics) | Frontend redirect to user-allowed company list |
| `503` | `schema_resolver_miss` | Resolved schema does not exist (tenant DB drop, schema rename, etc.) + `report_resolved_schema_miss` metric increment | Frontend retry button + ops alert (3 alerts in Plan §3.4) |
| `503` (degraded mode) | `schema_truth_fallback` | Plan §3.8 fallback chain triggered (schema-service unreachable + 30-day-old snapshot used + service NOT degraded gracefully) | Same as 503 above |

---

## 4. Test plan

### 4.1 Unit tests (per component)

| Test | Senaryo |
|---|---|
| `TenantHeaderExtractor_returnsEmptyWhenAbsent` | header eksik → Optional.empty() |
| `TenantHeaderExtractor_throwsOnNonNumeric` | "abc" → 400 invalid |
| `CurrentTenantSchemaResolver_resolvesForGivenCompany` | companyId=35 → ["workcube_mikrolink_35"] |
| `CurrentTenantSchemaResolver_throwsWhenContextLacksCompany` | TenantContext.empty → 400 required |
| `YearlySchemaResolver_failsClosedForSchemaMode_whenCompanyAbsent` | mode=schema + ctx.companyId=null → 400 |
| `YearlySchemaResolver_legacyExtract_onlyForStaticMode` | mode=static + sourceSchema set → resolves |
| `YearlySchemaResolver_failsClosedForSchemaMode_whenSourceSchemaSet` | mode=schema + def.sourceSchema set + ctx.companyId=null → 400 (no fallback) |
| `SchemaResolverFactory_returnsByResolverIdConfig` | def.tenantBoundary.schemaResolver=workcube-current-company → CurrentTenantSchemaResolver |

### 4.2 Integration tests (Spring `@WebMvcTest`)

| Test | Senaryo |
|---|---|
| `TenantBoundaryGuard_superAdminWithoutHeader_returns400` | `scope=ALL` + tenant-bound rapor + header eksik → 400 |
| `TenantBoundaryGuard_singleCompanyUserAutoPicked` | `scope=COMPANY` + user.companies=[35] + header eksik → server resolves to 35 |
| `TenantBoundaryGuard_multiCompanyUserWithoutHeader_returns400` | `scope=COMPANY` + user.companies=[35, 36] + header eksik → 400 |
| `TenantBoundaryGuard_resolvedSchemaMissReturns503` | header=99 (nonexistent company) + schema-service !exists(workcube_mikrolink_99) → 503 + metric increment |

### 4.3 End-to-end Testcontainers IT (optional Q7 alternative)

| Test | Senaryo |
|---|---|
| `ReportEndpointTenantGuardE2E_pickCompanyAndQueryGroupedSucceeds` | Spring Boot + MSSQL Testcontainers + schema-service mock + 1 company → fin-muhasebe-detay grouped query succeeds |

---

## 5. Out of scope (gelecek PR / Phase 2 başka programlar)

- **Build-time validator** (RC-001..011) — Phase 2 Program 1 spec PR #91 (cross-link)
- **Action menu standard** — Phase 2 Program 3 (ayrı spec)
- **Multi-table composition governance** — Phase 2 Program 7
- **Filter/Sort/Grouping correctness** — Phase 2 Program 6 (6b PR-0 chain'inde merged)
- **Schema-service availability fallback chain** — Plan §3.8 Program 8 (cross-cutting; bu spec consumer)

---

## 6. Rollback semantics

Bu Tenant Guard merge edilirse + 1 mevcut rapor `tenantBoundary` field'ı eksikse: validator FAIL üretir (Phase 2 Program 1'in RC-001..011'inden biri). Önce Phase 2 Program 1 + tüm rapor `tenantBoundary` field'ı set edilmiş olmalı.

Rollback path: `TenantBoundaryGuard` HandlerInterceptor disable edilir → eski `extractCompanyFromSchema()` fallback path geri açılır. Plan §3.2 Outcome zarar görür (boundary breach risk geri gelir).

Plan v2.1 §1 v3 revize prensibi: "Flag off does NOT silently fall back to flat rows" — burada da silent fallback yasak. Disable etmek explicit `application.properties` flag'i ile (`reporting.tenant-guard.enabled=false`) + WARN log + metric.

---

## 7. Risk / Trade-off Matrix

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Mevcut tenant-bound rapor flow'unda 400 patlaması (legacy `extractCompanyFromSchema` müşterileri) | M | Production downtime | Pre-merge dry-run + feature flag (`reporting.tenant-guard.enabled=false` default off until Plan §3.1 RC-001..011 tüm raporlarda PASS) |
| Schema-service rate limiting (her request schema existence check) | M | Latency p95 artış | 5-min Caffeine cache `schemaSnapshot` (Plan §3.8 default); per-request schemaExists() cache hit rate %99+ |
| `TenantContext` ThreadLocal leak (request after request) | L | Cross-request boundary breach | postHandle + afterCompletion ile `TenantContext.clear()` ZORUNLU; ThreadLocal interceptor pattern doğru kullanım |
| `X-Selected-Company-Id` MDC leak to logs | L | Privacy/audit issue | TenantGuardLogContext sadece `selectedCompanyId` ve `schemaMode` MDC'ye basar; userId zaten JWT context'inde |
| Frontend uyumsuzluk: client `X-Selected-Company-Id` göndermiyor | H | Phase 2 Program 2 merge sonrası tüm tenant-bound rapor 400 verir | mfe-reporting'de header injection middleware eklenmesi gerekir; frontend canary ile staged rollout |

---

## 8. Definition of Done

- [ ] `TenantBoundaryGuard` HandlerInterceptor + Spring config (`WebMvcConfigurer.addInterceptors`)
- [ ] `TenantContext` ThreadLocal + try/finally cleanup
- [ ] `TenantHeaderExtractor` + 400 invalid path
- [ ] `CurrentTenantSchemaResolver` (NEW) + factory registration
- [ ] `YearlySchemaResolver` hardening: `mode=schema` fallback YOK
- [ ] `SchemaResolverFactory` + `tenantBoundary.schemaResolver` lookup
- [ ] `report_resolved_schema_miss` + `schema_truth_fallback_total` metrics
- [ ] `TenantSelectionException` + 400/403/503 hierarchy
- [ ] 8 unit test PASS
- [ ] 4 `@WebMvcTest` IT PASS
- [ ] Feature flag default OFF until Phase 2 Program 1 + tüm rapor `tenantBoundary` field set
- [ ] Frontend mfe-reporting `X-Selected-Company-Id` header injection middleware (separate PR)
- [ ] ADR `docs/adr/0007-runtime-tenant-guard.md` (Plan §3.2 mandate)
- [ ] Codex post-impl peer review AGREE (HARD RULE Cross-AI)
- [ ] CI 9/9 green + admin merge YASAK

---

## 9. Owner Karar Soruları (özet)

Bu spec'i okumak için 5 dakika ayırıp şu 7 soruya cevap verirseniz implementation başlayabilir:

1. **Header eksikse**: 400 fail-closed (default) mı, 303 redirect mi?
2. **Single-company user**: server auto-pick (default) mi, client-set zorunlu mu?
3. **`extractCompanyFromSchema()` legacy scope**: sadece `static` raporlar (default) mı, geniş mi?
4. **Schema existence check fail**: 503 (default) mi, stale snapshot fallback + WARN mi?
5. **Filter chain ordering**: HandlerInterceptor (default) mı, `@ControllerAdvice` mi?
6. **`report_resolved_schema_miss` cardinality**: `{report_key, schema_mode}` (default) mu, tenant_id label da dahil mi?
7. **Acceptance**: 4 `@WebMvcTest` IT (default) mi, sadece unit + 1 e2e mi?

Default önerileri seçerseniz "AGREE → impl başla" cevabı yeterlidir.

---

## 10. Sub-PR breakdown

1. **Phase-2-Program-2a**: TenantBoundaryGuard + TenantContext + TenantHeaderExtractor + 8 unit test
2. **Phase-2-Program-2b**: CurrentTenantSchemaResolver (NEW) + SchemaResolverFactory + 3 unit test
3. **Phase-2-Program-2c**: YearlySchemaResolver hardening (`mode=schema` fallback YOK) + 3 unit test
4. **Phase-2-Program-2d**: 4 `@WebMvcTest` IT + Spring config wiring
5. **Phase-2-Program-2e**: ReportRuntimeMetrics + TenantGuardLogContext MDC + ADR-0007 + feature flag

5 sub-PR, her biri bağımsız Codex iter cycle, normal merge (admin merge YASAK), sırasıyla.

---

## 11. Plan v2.1 referans çapraz-link

- §3.2 `Program 2 — Runtime Tenant Guard` — bu spec
- §3.1 `Program 1 — Report Contract Gate` — Program 1'in `tenantBoundary` field'ı bu spec'in girdisi
- §3.8 `Program 8 — Schema Truth Integration` — schema-service `schemaExists()` + 3-tier fallback
- §3.4 `Program 4 — Operational Observability` — `report_resolved_schema_miss` metric Plan §9.3'te tanımlanmış 3 alert'ten biri
- §1 v3 revize iter-15 prensibi: silent flat fallback YASAK — Tenant Guard 503 explicit, no graceful degrade

---

## 12. Next Step

1. Owner UX feedback (Q1-Q7).
2. Codex iter-N cross-AI peer review (spec sonrası, impl-time karar açılışı).
3. Sub-PR breakdown'a göre Phase-2-Program-2a impl başlar (Phase 2 Program 1 merge'inden sonra — mevcut rapor tenantBoundary field set edilmiş olmalı).
4. Per-PR Codex AGREE → CI green → normal merge.
