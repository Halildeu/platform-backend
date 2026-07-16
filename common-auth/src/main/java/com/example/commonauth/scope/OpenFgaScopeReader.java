package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3):
 * reusable OpenFGA scope-fetch helper extracted from
 * {@link ScopeContextFilter#fetchScopeFromOpenFga(String)} so
 * permission-service controllers (which do NOT register
 * ScopeContextFilter in their request path) can read the same
 * authoritative scope view without re-implementing the parallel
 * fetch + cache + relation map. Used by:
 *
 * <ul>
 *   <li>{@link ScopeContextFilter} — request-scope binding (existing
 *       caller, now delegates here)</li>
 *   <li>{@code UserScopeService.listUserScopes(userId)} — admin
 *       read-path migration (Phase 4)</li>
 *   <li>{@code AuthorizationQueryService.getUserScopeSummary(userId)} —
 *       /authz/me read-path migration (Phase 4)</li>
 * </ul>
 *
 * <p>Source-of-truth correctness: with PR-BE-9 (Phase 1+2) aligning
 * WRITE relations on {@code viewer} for all scope object types, this
 * reader's READ relations match exactly what
 * {@link com.example.permission.service.TupleSyncService#syncScopeTuples(
 * String, java.util.List, java.util.List, java.util.List, java.util.List, boolean)}
 * writes — closing the persistence-store split that surfaced as the
 * "şirket yetkileri kayıt olmuyor" production bug.
 *
 * <p>Cache reuse: when {@link ScopeContextCache} and
 * {@link AuthzVersionProvider} are wired, this reader uses the same
 * Caffeine cache as {@link ScopeContextFilter} (key:
 * {@code userId:v{authzVersion}:s{storeId}:m{modelId}}), so admin reads
 * for user X benefit from a recent request-scope cache hit for the same
 * user X. Cache is invalidated on tuple writes via
 * {@link ScopeContextCache#evictUser(String)}.
 *
 * <p>Failure semantics — two contracts on the same fetch path:
 * <ul>
 *   <li>{@link #readScopeContext} (strict): propagates the underlying
 *       OpenFGA exception. Used by {@link ScopeContextFilter} which
 *       wraps the throw and substitutes its legacy "production OpenFGA
 *       fail → dev scope" fallback (preserves pre-PR-BE-10 request-
 *       binding behavior).</li>
 *   <li>{@link #readScopeSummarySafe} (admin): catches the exception
 *       and returns an empty map. Used by {@code UserScopeService}
 *       and {@code AuthorizationQueryService} where the UI prefers a
 *       deterministic "no scopes" view over a 5xx; the empty list has
 *       the same shape as a legitimately scope-less user.</li>
 * </ul>
 */
public class OpenFgaScopeReader {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaScopeReader.class);

    private final OpenFgaAuthzService authzService;
    private final OpenFgaProperties properties;
    @Nullable private final ScopeContextCache scopeContextCache;
    @Nullable private final AuthzVersionProvider versionProvider;

    public OpenFgaScopeReader(OpenFgaAuthzService authzService, OpenFgaProperties properties) {
        this(authzService, properties, null, null);
    }

    public OpenFgaScopeReader(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              @Nullable ScopeContextCache scopeContextCache,
                              @Nullable AuthzVersionProvider versionProvider) {
        this.authzService = authzService;
        this.properties = properties;
        this.scopeContextCache = scopeContextCache;
        this.versionProvider = versionProvider;
    }

    /**
     * Reads a complete {@link ScopeContext} for the given user from
     * OpenFGA. Throws on OpenFGA outage so callers can choose how to
     * degrade: {@link ScopeContextFilter} maps the throw to its
     * "production OpenFGA fail → dev scope" legacy fallback;
     * admin-side callers (UserScopeService, AuthorizationQueryService)
     * use {@link #readScopeSummarySafe} which catches and returns empty.
     *
     * <p>Cache integration: if both {@link ScopeContextCache} and
     * {@link AuthzVersionProvider} are wired, reads consult the
     * Caffeine cache first (key includes userId + authz version + store
     * + model). Cache miss triggers parallel fetch + put.
     *
     * @param userId numeric DB user id (string form, e.g. "1204")
     * @return ScopeContext with allowed scope IDs and superAdmin flag
     * @throws RuntimeException on OpenFGA outage / fetch failure
     */
    public ScopeContext readScopeContext(String userId) {
        return readScopeContext(userId, null);
    }

    /**
     * Dual-subject variant (board #2531).
     *
     * <p><b>Why:</b> two writers store tuples with different subject forms for the SAME verified
     * user. {@code TupleSyncService} (role/permission derived) writes {@code user:<numeric DB id>};
     * the canonical data-access grant path ({@code POST /api/v1/access/scope} &rarr;
     * {@code DataAccessScopeTupleEncoder}) writes {@code user:<Keycloak sub UUID>}. Reading with
     * only one form makes the other writer's grants invisible — the grant API returns 201 and the
     * user still gets 403, with no error anywhere.
     *
     * <p>This is a <b>transition</b> read: both ids must already denote the same authenticated
     * principal (numeric id resolved from the verified token; sub taken from the signed JWT), so
     * the union cannot widen anyone's access — it only stops losing that principal's own tuples.
     * Long term the subject converges on the validated KC {@code sub} and this alias read is removed.
     *
     * @param userId      primary subject (numeric DB user id, string form)
     * @param aliasUserId additional VERIFIED subject for the same principal (KC {@code sub}), or
     *                    {@code null}/blank when unavailable
     */
    public ScopeContext readScopeContext(String userId, String aliasUserId) {
        if (userId == null || userId.isBlank()) {
            return ScopeContext.empty(userId);
        }
        if (!properties.isEnabled()) {
            // openfga.enabled=false → caller should not call this reader.
            // Return empty rather than throw so existing callers get a
            // safe default if they're misconfigured.
            log.debug("OpenFgaScopeReader called but properties.enabled=false; returning empty scope");
            return ScopeContext.empty(userId);
        }

        String alias = (aliasUserId == null || aliasUserId.isBlank() || aliasUserId.equals(userId))
                ? null : aliasUserId;

        ScopeContextCache cache = scopeContextCache;
        AuthzVersionProvider vp = versionProvider;
        if (cache != null && cache.isEnabled() && vp != null) {
            long version = vp.getCurrentVersion();
            // The alias participates in the cache key: a single-subject read and a dual-subject
            // read can legitimately yield different scope sets, so they must not share an entry.
            //
            // Separator MUST be ':' and the primary id MUST stay first — ScopeContextCache
            // .evictUser(userId) invalidates by the prefix "<userId>:". A key like "1204|<sub>:v1"
            // would NOT match that prefix and would survive tuple-write eviction, leaving the user
            // with a stale (empty) scope right after a grant — i.e. re-creating the very bug this
            // slice fixes. "1204:<sub>:v1" is matched by the existing prefix eviction.
            String cacheSubject = alias == null ? userId : userId + ":" + alias;
            String cacheKey = ScopeContextCache.cacheKey(
                    cacheSubject, version, properties.getStoreId(), properties.getModelId());
            ScopeContext cached = cache.get(cacheKey);
            if (cached != null) {
                log.debug("OpenFgaScopeReader cache HIT for user:{}", cacheSubject);
                return cached;
            }
            ScopeContext fresh = fetchFromOpenFga(userId, alias);
            cache.put(cacheKey, fresh);
            return fresh;
        }

        return fetchFromOpenFga(userId, alias);
    }

    /**
     * Safe variant for admin-side callers: returns empty scope summary
     * on OpenFGA outage rather than propagating the exception. Use this
     * from {@code UserScopeService.listUserScopes} and
     * {@code AuthorizationQueryService.getUserScopeSummary} where the
     * UI prefers a deterministic "no scopes" view to a 5xx.
     */
    public Map<String, Set<Long>> readScopeSummarySafe(String userId) {
        return readScopeSummarySafe(userId, null);
    }

    /** Dual-subject safe variant — see {@link #readScopeContext(String, String)} (board #2531). */
    public Map<String, Set<Long>> readScopeSummarySafe(String userId, String aliasUserId) {
        try {
            return readScopeSummary(userId, aliasUserId);
        } catch (Exception e) {
            log.warn("OpenFgaScopeReader summary fetch failed for user:{}; returning empty. cause={}",
                    userId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    /**
     * Reads a permission-free scope summary keyed by scope type
     * ({@code COMPANY}, {@code PROJECT}, {@code WAREHOUSE},
     * {@code BRANCH}). Used by {@code AuthorizationQueryService} for
     * the {@code /authz/me} response and by admin endpoints that need
     * per-type lists without the superAdmin flag.
     *
     * <p>PR-BE-14 (Codex thread 019e0938 iter-7 AGREE absorb,
     * 2026-05-08): "allowedScopes" semantics revisited. Pre-fix this
     * method returned an EMPTY map for superAdmin (matching the legacy
     * DB-backed getUserScopeSummary which had no per-resource scope
     * rows for superAdmins). The legacy behaviour produced a UX bug on
     * the testai UserDetailDrawer "Veri Erişimi → Şirketler" tab:
     * superAdmin saved companies, OpenFGA tuples persisted (writes
     * succeeded), but reload showed an empty list — admin observability
     * broken, "kaydetmedi gibi" perception bug.
     *
     * <p>Post-fix: {@code allowedScopes} now means "explicit assigned
     * scopes / observable assignments" — populated for ALL users,
     * including superAdmin. Policy decisions still treat
     * {@code superAdmin=true} as universal bypass via
     * {@link ScopeContext#canAccessCompany}/Project/Warehouse/Branch
     * (which short-circuit on superAdmin), so the bypass guarantee is
     * preserved. The scope summary's role narrows to "what was
     * actually assigned in OpenFGA", not "what the user can access".
     */
    public Map<String, Set<Long>> readScopeSummary(String userId) {
        return readScopeSummary(userId, null);
    }

    /** Dual-subject variant — see {@link #readScopeContext(String, String)} (board #2531). */
    public Map<String, Set<Long>> readScopeSummary(String userId, String aliasUserId) {
        ScopeContext ctx = readScopeContext(userId, aliasUserId);
        Map<String, Set<Long>> result = new LinkedHashMap<>();
        if (!ctx.allowedCompanyIds().isEmpty()) {
            result.put("COMPANY", new LinkedHashSet<>(ctx.allowedCompanyIds()));
        }
        if (!ctx.allowedProjectIds().isEmpty()) {
            result.put("PROJECT", new LinkedHashSet<>(ctx.allowedProjectIds()));
        }
        if (!ctx.allowedWarehouseIds().isEmpty()) {
            result.put("WAREHOUSE", new LinkedHashSet<>(ctx.allowedWarehouseIds()));
        }
        if (!ctx.allowedBranchIds().isEmpty()) {
            result.put("BRANCH", new LinkedHashSet<>(ctx.allowedBranchIds()));
        }
        return result;
    }

    /**
     * Parallel OpenFGA fetch — 4 listObjectIds + 1 admin check, joined
     * via CompletableFuture.allOf so latency = max(individual call)
     * rather than sum.
     *
     * <p>Relation map (PR-BE-9 aligned, all object types use
     * {@code viewer}; superAdmin uses {@code admin/organization/default}):
     * <ul>
     *   <li>company → viewer</li>
     *   <li>project → viewer</li>
     *   <li>warehouse → viewer</li>
     *   <li>branch → viewer</li>
     * </ul>
     */
    private ScopeContext fetchFromOpenFga(String userId) {
        return fetchFromOpenFga(userId, null);
    }

    /**
     * @param alias verified second subject for the same principal, or {@code null}; results are
     *              unioned so neither writer's tuples are lost (board #2531)
     */
    private ScopeContext fetchFromOpenFga(String userId, String alias) {
        var companyFuture = CompletableFuture.supplyAsync(
                () -> unionObjectIds(userId, alias, "viewer", "company"));
        var projectFuture = CompletableFuture.supplyAsync(
                () -> unionObjectIds(userId, alias, "viewer", "project"));
        var warehouseFuture = CompletableFuture.supplyAsync(
                () -> unionObjectIds(userId, alias, "viewer", "warehouse"));
        var branchFuture = CompletableFuture.supplyAsync(
                () -> unionObjectIds(userId, alias, "viewer", "branch"));
        var adminFuture = CompletableFuture.supplyAsync(
                () -> authzService.check(userId, "admin", "organization", "default")
                        || (alias != null && authzService.check(alias, "admin", "organization", "default")));

        CompletableFuture.allOf(companyFuture, projectFuture, warehouseFuture, branchFuture, adminFuture).join();

        // PR-BE-14 (Codex thread 019e0938 iter-7 AGREE absorb, 2026-05-08):
        // populate allowed*Ids even when superAdmin=true. The four
        // listObjectIds futures already ran in parallel; discarding
        // their results for superAdmin (the previous behaviour) was the
        // root cause of the UserDetailDrawer "kaydetmedi gibi" UX bug
        // — admins couldn't see assignments they had just persisted.
        // The bypass guarantee at policy decision points stays intact
        // because {@link ScopeContext#canAccessCompany}/Project/etc.
        // short-circuit on the superAdmin flag, regardless of what's
        // in the explicit allowed*Ids sets.
        boolean isSuperAdmin = adminFuture.join();
        return new ScopeContext(userId,
                companyFuture.join(),
                projectFuture.join(),
                warehouseFuture.join(),
                branchFuture.join(),
                isSuperAdmin);
    }

    /**
     * Union of a relation's object ids across the principal's verified subject forms.
     *
     * <p>Both ids denote the SAME authenticated principal (board #2531): {@code userId} is the
     * numeric DB id resolved from the verified token, {@code alias} is the KC {@code sub} from the
     * signed JWT. Unioning therefore cannot grant anything the principal was not already granted —
     * it stops one writer's tuples from being invisible to the reader.
     */
    private Set<Long> unionObjectIds(String userId, String alias, String relation, String objectType) {
        Set<Long> ids = new LinkedHashSet<>(authzService.listObjectIds(userId, relation, objectType));
        if (alias != null) {
            ids.addAll(authzService.listObjectIds(alias, relation, objectType));
        }
        return ids;
    }

}
