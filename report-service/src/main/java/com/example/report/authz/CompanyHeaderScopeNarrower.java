package com.example.report.authz;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Narrows an {@link AuthzMeResponse} to a single {@code COMPANY} scope
 * when the caller selects one via the {@code X-Company-Id} request header.
 *
 * <p><b>Why</b>: muavin / dynamic-report users often have access to several
 * Workcube tenants (e.g. allowedScopes COMPANY: 1, 5, 7). Without
 * narrowing, every report query expands to a UNION across all of them
 * (see {@link com.example.report.query.YearlySchemaResolver}), so the
 * grid mixes data from companies the user did not explicitly select.
 * The {@code CompanyPicker} dropdown sets
 * {@code localStorage[reporting:currentCompanyId]} and the API client
 * forwards it as {@code X-Company-Id}; this class is the one place the
 * backend turns that header into a query-shape narrowing.
 *
 * <p><b>Authorization rules</b>:
 * <ul>
 *   <li>Header missing or blank → no narrowing, original authz returned.</li>
 *   <li>Super-admin → header value accepted as-is and used as the
 *       singleton COMPANY scope (super-admin can select any tenant).</li>
 *   <li>Scoped user → header value must be in the user's existing
 *       {@code allowedScopes COMPANY} set; otherwise a 403 is thrown.
 *       Other scope types (PROJECT, WAREHOUSE, BRANCH) pass through
 *       untouched.</li>
 *   <li>Header value not numeric → 400.</li>
 * </ul>
 *
 * <p><b>Why a fresh AuthzMeResponse</b>: callers (controllers) hold the
 * original authz object too — for permission checks, audit logging, etc.
 * Mutating it would leak the narrowing into call sites that didn't ask
 * for it. We return a copy.
 */
@Component
public class CompanyHeaderScopeNarrower {

    private static final Logger log = LoggerFactory.getLogger(CompanyHeaderScopeNarrower.class);

    /** Standard header name; must match {@code dynamic-report/api.ts COMPANY_HEADER}. */
    public static final String HEADER_NAME = "X-Company-Id";

    /**
     * Narrow {@code original} so that COMPANY scope contains exactly
     * {@code companyHeader}. Returns the original object when the header
     * is missing or blank.
     *
     * @throws ResponseStatusException 400 if the header is not numeric
     * @throws ResponseStatusException 403 if a scoped user requests a
     *         company they don't have access to
     */
    public AuthzMeResponse narrow(AuthzMeResponse original, String companyHeader) {
        if (original == null) {
            return null;
        }
        if (companyHeader == null || companyHeader.isBlank()) {
            return original;
        }
        String trimmed = companyHeader.trim();
        // Quick numeric guard — Workcube COMPANY ids are integers.
        try {
            Integer.parseInt(trimmed);
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "X-Company-Id must be numeric, got: " + trimmed);
        }

        if (!original.isSuperAdmin()) {
            Set<String> allowed = original.getScopeRefIds("COMPANY");
            if (!allowed.contains(trimmed)) {
                log.warn("CompanyHeaderScopeNarrower: user {} requested company {} but allowed={}",
                        original.getUserId(), trimmed, allowed);
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Company " + trimmed + " is not in your scope");
            }
        }

        AuthzMeResponse narrowed = new AuthzMeResponse();
        narrowed.setUserId(original.getUserId());
        narrowed.setPermissions(original.getPermissions());
        narrowed.setSuperAdmin(original.getSuperAdmin());
        narrowed.setReports(original.getReports());

        List<ScopeSummaryDto> scopes = new ArrayList<>();
        // Singleton COMPANY scope — every COMPANY-typed entry is replaced
        // with exactly the requested id.
        scopes.add(new ScopeSummaryDto("COMPANY", trimmed));
        // Preserve every non-COMPANY scope from the original (PROJECT,
        // WAREHOUSE, BRANCH, etc.) — we only narrow the company axis.
        if (original.getAllowedScopes() != null) {
            for (ScopeSummaryDto s : original.getAllowedScopes()) {
                if (s == null || s.getScopeType() == null) continue;
                if (!"COMPANY".equalsIgnoreCase(s.getScopeType())) {
                    scopes.add(s);
                }
            }
        }
        narrowed.setAllowedScopes(scopes);
        log.debug("CompanyHeaderScopeNarrower: user {} narrowed to COMPANY={} (superAdmin={})",
                original.getUserId(), trimmed, original.isSuperAdmin());
        return narrowed;
    }
}
