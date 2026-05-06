package com.example.report.workcube;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.example.commonauth.scope.ScopeContext;

/**
 * Authorization-aware wrapper around {@link CompanyOptionsRepository}.
 *
 * <p>Caching strategy (per Codex 019dfb0b iter-1 review):
 * <ul>
 *   <li>The raw catalog from MSSQL ({@link CompanyOptionsRepository#findAll()})
 *       is cached <b>globally</b> for {@code report.workcube.company-options.cache-ttl}
 *       (default 5 minutes via the {@code companyOptions} cache name).</li>
 *   <li>The per-request authorization filter runs on top of the cached list —
 *       cheap (43 items) and avoids the per-user cache invalidation problem
 *       that would otherwise tie staleness to {@code authzVersion}.</li>
 * </ul>
 *
 * <p>Authorization model:
 * <ul>
 *   <li>Super-admin: returns the full catalog.</li>
 *   <li>Scoped user: filters down to {@code ScopeContext.allowedCompanyIds()}.</li>
 *   <li>Anonymous / no scope context: returns empty list (controller surfaces
 *       this as 401 if it slipped past Spring Security).</li>
 * </ul>
 */
@Service
@ConditionalOnBean(CompanyOptionsRepository.class)
public class CompanyOptionsService {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsService.class);

    private final CompanyOptionsRepository repository;

    public CompanyOptionsService(CompanyOptionsRepository repository) {
        this.repository = repository;
    }

    /** Cached raw catalog. Cache name {@code companyOptions}. */
    @Cacheable(cacheNames = "companyOptions", sync = true)
    public List<CompanyOptionsRepository.CompanyOption> findAllCached() {
        return repository.findAll();
    }

    /**
     * Returns the catalog filtered by the caller's scope.
     *
     * @param scope authorization context — must not be null in production
     *              (Spring Security guarantees this on /api/* paths via
     *              {@code ScopeContextFilter}).
     */
    public List<CompanyOptionsRepository.CompanyOption> findAuthorized(ScopeContext scope) {
        if (scope == null) {
            log.warn("CompanyOptionsService: ScopeContext null — returning empty");
            return Collections.emptyList();
        }
        List<CompanyOptionsRepository.CompanyOption> all = findAllCached();
        if (scope.superAdmin()) {
            return all;
        }
        Set<Long> allowedIds = scope.allowedCompanyIds();
        if (allowedIds == null || allowedIds.isEmpty()) {
            return Collections.emptyList();
        }
        return all.stream()
                .filter(opt -> allowedIds.contains((long) opt.id()))
                .toList();
    }
}
