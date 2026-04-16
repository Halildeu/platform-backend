package com.example.report.authz;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

/**
 * {@link PermissionResolver} implementation backed by {@link OpenFgaAuthzService}
 * (direct OpenFGA SDK) — replaces the legacy HTTP call to
 * permission-service's {@code /api/v1/authz/me} snapshot endpoint.
 *
 * <p>PR6c-1 (CNS-20260416-003, D-008 / C-008 compliance). Behavior-preserving:
 * the returned {@link AuthzMeResponse} shape is identical to the HTTP snapshot,
 * so {@code ReportAccessEvaluator}, {@code ColumnFilter}, {@code QueryEngine},
 * {@code RowFilterInjector} and {@code YearlySchemaResolver} continue to work
 * without modification.
 *
 * <p>Resolution steps:
 * <ol>
 *   <li>User id via {@link AuthenticatedUserLookupService#resolve(Jwt)} — NOT
 *       {@code jwt.getSubject()}: the numeric/internal user id is required for
 *       OpenFGA tuples ({@code user:<numericId>}) and must match how
 *       permission-service writes tuples.</li>
 *   <li>Snapshot cache lookup keyed on {@code userId + authzVersion}. A cache
 *       hit returns the cached response; misses fall through.</li>
 *   <li>Super-admin probe via {@code admin@organization:default} — short-circuit
 *       permissions/scopes on true.</li>
 *   <li>Up-front {@code check()} per known static permission (module +
 *       dashboard + scope-marker + column). Populates {@code permissions}.</li>
 *   <li>{@code listObjects(can_view, report)} bulk call to populate both
 *       {@code reports} map (keyed by OpenFGA object id) and the
 *       {@code reports.<key>.view} / {@code dashboards.<key>.view} entries in
 *       the legacy {@code permissions} list.</li>
 *   <li>{@code allowedScopes} from {@link ScopeContextHolder} — the
 *       {@link com.example.commonauth.scope.ScopeContextFilter} (wired by
 *       {@link com.example.report.config.OpenFgaAuthzConfig}) has already
 *       populated the thread-local for {@code /api/*} requests.</li>
 * </ol>
 *
 * <p>When OpenFGA is disabled (dev / local profile,
 * {@code erp.openfga.enabled=false}), {@code OpenFgaAuthzService} returns
 * {@code true} from {@link OpenFgaAuthzService#check(String, String, String, String)}
 * — the super-admin shortcut lights up and the adapter yields a permissive
 * snapshot without touching an OpenFGA server.
 */
@Component
@Primary
public class OpenFgaAuthzMeBuilder implements PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(OpenFgaAuthzMeBuilder.class);

    private final OpenFgaAuthzService openFga;
    private final AuthenticatedUserLookupService userLookup;
    private final PermissionCodeToTupleMapper mapper;
    private final AuthzVersionProvider versionProvider;
    private final Cache<String, AuthzMeResponse> snapshotCache;

    public OpenFgaAuthzMeBuilder(OpenFgaAuthzService openFga,
                                  AuthenticatedUserLookupService userLookup,
                                  PermissionCodeToTupleMapper mapper,
                                  AuthzVersionProvider versionProvider,
                                  @Value("${authz.me.cache.ttl-seconds:30}") int ttlSeconds,
                                  @Value("${authz.me.cache.max-size:2000}") long maxSize) {
        this.openFga = openFga;
        this.userLookup = userLookup;
        this.mapper = mapper;
        this.versionProvider = versionProvider;
        this.snapshotCache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(ttlSeconds > 0 ? ttlSeconds : 30))
                .maximumSize(maxSize > 0 ? maxSize : 2000)
                .build();
    }

    @Override
    public AuthzMeResponse getAuthzMe(Jwt jwt) {
        if (jwt == null) {
            return emptyResponse(null);
        }

        // (1) Resolve internal numeric/user id — NOT jwt.getSubject() which can
        //     be a Keycloak uuid that has no OpenFGA tuples.
        var resolved = userLookup.resolve(jwt);
        String userId = resolved.responseUserId();
        if (userId == null || userId.isBlank()) {
            log.debug("authzMe: user lookup returned no id for jwt subject={}", jwt.getSubject());
            return emptyResponse(null);
        }

        // (2) Snapshot cache — (userId, authzVersion). Version bumps from
        //     permission-service (tuple sync) invalidate entries via a new key,
        //     TTL drains the old ones.
        long version = versionProvider.getCurrentVersion();
        String cacheKey = userId + ":" + version;
        AuthzMeResponse cached = snapshotCache.getIfPresent(cacheKey);
        if (cached != null) {
            log.debug("authzMe cache HIT user={} version={}", userId, version);
            return cached;
        }

        AuthzMeResponse response = new AuthzMeResponse();
        response.setUserId(userId);

        // (3) Super-admin probe — short-circuit the rest of the enumeration.
        boolean superAdmin = safeCheck(userId, "admin", "organization", "default");
        response.setSuperAdmin(superAdmin);

        List<String> permissions = new ArrayList<>();
        Map<String, String> reportsMap = new LinkedHashMap<>();

        if (superAdmin) {
            // Super-admin short-circuit: the legacy snapshot left permissions
            // empty (callers checked isSuperAdmin() first). Preserve that
            // shape — hasPermission() returns false but isSuperAdmin() gates
            // all authorization checks before falling through.
            response.setPermissions(permissions);
            response.setReports(reportsMap);
            response.setAllowedScopes(buildAllowedScopes(true));
            snapshotCache.put(cacheKey, response);
            return response;
        }

        // (4) Static permission enumeration — module / dashboard / scope-marker
        //     / column. Each maps to a known OpenFGA tuple; preserve the legacy
        //     code string in the permissions list so helper classes continue
        //     to compare against the same strings.
        probeAndCollect(userId, mapper.knownModulePermissions(), permissions);
        probeAndCollect(userId, mapper.knownDashboardPermissions(), permissions);
        probeAndCollect(userId, mapper.knownScopeMarkers(), permissions);
        probeAndCollect(userId, mapper.knownColumnPermissions(), permissions);

        // (5) Report-level: single listObjects call covers the entire catalog
        //     without per-report fan-out. Populates both the reports map and
        //     the legacy permissions list entries used by the codebase.
        List<String> allowedReportIds = safeListObjects(userId, "can_view", "report");
        for (String reportId : allowedReportIds) {
            reportsMap.put(reportId, "ALLOW");
            // Locale.ROOT — mirror the upperSnake() convention in
            // PermissionCodeToTupleMapper so SATIS_OZET ⇄ satis-ozet round-trips
            // correctly on Turkish-locale JVMs.
            String legacySlug = reportId.toLowerCase(Locale.ROOT).replace('_', '-');
            permissions.add("reports." + legacySlug + ".view");
            // Dashboard type shares the report bucket — keep the legacy
            // permission code form populated so hasPermission() hits.
            permissions.add("dashboards." + legacySlug + ".view");
        }

        response.setPermissions(permissions);
        response.setReports(reportsMap);

        // (6) Allowed scopes — ScopeContextFilter has already set the holder
        //     for /api/* requests. For endpoints that are reached without the
        //     filter (tests, boot probes, etc.) the holder is null and scopes
        //     fall back to empty — matching the legacy response in that case.
        response.setAllowedScopes(buildAllowedScopes(false));

        snapshotCache.put(cacheKey, response);
        log.debug("authzMe cache MISS user={} version={} perms={} reports={}",
                userId, version, permissions.size(), reportsMap.size());
        return response;
    }

    // ---- helpers ---------------------------------------------------------

    private void probeAndCollect(String userId, Set<String> codes, List<String> out) {
        for (String code : codes) {
            mapper.toTuple(code).ifPresent(t -> {
                if (safeCheck(userId, t.relation(), t.objectType(), t.objectId())) {
                    out.add(code);
                }
            });
        }
    }

    private boolean safeCheck(String userId, String relation, String objectType, String objectId) {
        try {
            return openFga.check(userId, relation, objectType, objectId);
        } catch (RuntimeException ex) {
            // OpenFGA circuit breaker handles cascades; local guard keeps the
            // snapshot build from aborting on a single query failure.
            log.warn("authzMe check failed user={} rel={} obj={}:{} — {}",
                    userId, relation, objectType, objectId, ex.getMessage());
            return false;
        }
    }

    private List<String> safeListObjects(String userId, String relation, String objectType) {
        try {
            return openFga.listObjects(userId, relation, objectType);
        } catch (RuntimeException ex) {
            log.warn("authzMe listObjects failed user={} rel={} type={} — {}",
                    userId, relation, objectType, ex.getMessage());
            return List.of();
        }
    }

    private List<ScopeSummaryDto> buildAllowedScopes(boolean superAdmin) {
        // Super-admin snapshot intentionally leaves allowedScopes empty — the
        // legacy PermissionServiceClient response did the same (callers check
        // isSuperAdmin() first, bypassing scope evaluation entirely).
        if (superAdmin) {
            return new ArrayList<>();
        }
        ScopeContext ctx = ScopeContextHolder.get();
        List<ScopeSummaryDto> scopes = new ArrayList<>();
        if (ctx == null) {
            return scopes;
        }
        if (ctx.allowedCompanyIds() != null) {
            ctx.allowedCompanyIds().forEach(id ->
                    scopes.add(new ScopeSummaryDto("company", String.valueOf(id))));
        }
        if (ctx.allowedProjectIds() != null) {
            ctx.allowedProjectIds().forEach(id ->
                    scopes.add(new ScopeSummaryDto("project", String.valueOf(id))));
        }
        if (ctx.allowedWarehouseIds() != null) {
            ctx.allowedWarehouseIds().forEach(id ->
                    scopes.add(new ScopeSummaryDto("warehouse", String.valueOf(id))));
        }
        if (ctx.allowedBranchIds() != null) {
            ctx.allowedBranchIds().forEach(id ->
                    scopes.add(new ScopeSummaryDto("branch", String.valueOf(id))));
        }
        return scopes;
    }

    private AuthzMeResponse emptyResponse(String userId) {
        AuthzMeResponse r = new AuthzMeResponse();
        r.setUserId(userId);
        r.setSuperAdmin(false);
        r.setPermissions(new ArrayList<>());
        r.setReports(new HashMap<>());
        r.setAllowedScopes(new ArrayList<>());
        return r;
    }
}
