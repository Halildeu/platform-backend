package com.example.report.workcube;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.commonauth.scope.ScopeContext;

/**
 * Read-only catalog endpoint for the muavin / dynamic-report
 * CompanyPicker dropdown.
 *
 * <p>Path: {@code GET /api/v1/reports/company-options}
 *
 * <p>Path is intentionally report-owned (not the platform-wide
 * {@code /api/v1/companies}, which is gated to {@code core-data-service}
 * via the api-gateway route table). Per Codex 019dfb0b iter-1: keeping
 * Workcube catalog separate avoids confusion between the platform's
 * native company API and the per-tenant Workcube schema list.
 *
 * <p>Authorization:
 * <ul>
 *   <li>Caller must be authenticated (Spring Security on /api/*).</li>
 *   <li>Super-admin → full catalog.</li>
 *   <li>Scoped user → filtered to {@link ScopeContext#allowedCompanyIds()}.
 *       Frontend gets exactly the same list it would get from a
 *       backend-side scope check, so the dropdown can't show entries the
 *       user couldn't load anyway.</li>
 * </ul>
 *
 * <p>Note that returning a filtered list is a UX guard, not a security
 * guard — every report metadata/data/export call still re-checks
 * {@code X-Company-Id} server-side.
 *
 * <p>Activation: same conditional chain as the rest of {@code workcube/}
 * — {@link CompanyOptionsService} only registers when
 * {@link CompanyOptionsRepository} does, which in turn requires
 * {@code workcubeMssqlDataSource} (feature flag {@code report.mssql.enabled}).
 * When the feature flag is off, the entire path 404s — that's fine, the
 * frontend falls back to the static {@code Şirket #1..43} list.
 */
@RestController
@RequestMapping("/api/v1/reports/company-options")
@ConditionalOnBean(CompanyOptionsService.class)
public class CompanyOptionsController {

    private static final Logger log = LoggerFactory.getLogger(CompanyOptionsController.class);

    private final CompanyOptionsService service;

    public CompanyOptionsController(CompanyOptionsService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<CompanyOptionsRepository.CompanyOption>> list() {
        ScopeContext scope = ScopeContext.current();
        if (scope == null) {
            // Spring Security on /api/* normally guarantees ScopeContext is
            // populated; this is a defensive 401 in case the filter chain
            // ordering ever drifts.
            return ResponseEntity.status(401).build();
        }
        List<CompanyOptionsRepository.CompanyOption> options = service.findAuthorized(scope);
        log.debug("CompanyOptionsController: returning {} options for user={} superAdmin={}",
                options.size(), scope.userId(), scope.superAdmin());
        return ResponseEntity.ok(options);
    }
}
