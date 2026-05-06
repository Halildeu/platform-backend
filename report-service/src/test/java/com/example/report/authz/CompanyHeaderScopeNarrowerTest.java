package com.example.report.authz;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link CompanyHeaderScopeNarrower}.
 *
 * <p>Covers the four buckets that matter to data isolation:
 * <ul>
 *   <li>Header missing/blank → original authz returned unchanged
 *       (callers without the X-Company-Id contract still work).</li>
 *   <li>Super-admin → header value accepted, COMPANY scope narrowed to it.</li>
 *   <li>Scoped user, allowed company → narrowed; other scope types
 *       (PROJECT, WAREHOUSE) preserved.</li>
 *   <li>Scoped user, disallowed company → 403.</li>
 *   <li>Non-numeric header → 400.</li>
 * </ul>
 */
class CompanyHeaderScopeNarrowerTest {

    private CompanyHeaderScopeNarrower narrower;

    @BeforeEach
    void setUp() {
        narrower = new CompanyHeaderScopeNarrower();
    }

    private AuthzMeResponse buildAuthz(boolean superAdmin, List<ScopeSummaryDto> scopes) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("user@example.com");
        authz.setSuperAdmin(superAdmin);
        authz.setAllowedScopes(scopes);
        return authz;
    }

    @Test
    void nullHeader_returnsOriginal() {
        AuthzMeResponse authz = buildAuthz(false, List.of(
                new ScopeSummaryDto("COMPANY", "1"),
                new ScopeSummaryDto("COMPANY", "5")));

        AuthzMeResponse result = narrower.narrow(authz, null);

        assertSame(authz, result, "no narrowing should be applied when header is null");
    }

    @Test
    void blankHeader_returnsOriginal() {
        AuthzMeResponse authz = buildAuthz(false, List.of(new ScopeSummaryDto("COMPANY", "1")));

        AuthzMeResponse result = narrower.narrow(authz, "   ");

        assertSame(authz, result);
    }

    @Test
    void superAdmin_anyCompany_narrowedToHeaderValue() {
        // Super-admin doesn't have explicit scopes, but should still be
        // narrowed to the picker selection so they query just one tenant.
        AuthzMeResponse authz = buildAuthz(true, List.of());

        AuthzMeResponse result = narrower.narrow(authz, "42");

        assertNotSame(authz, result, "narrowed object should be a copy, not the original");
        assertEquals(Set.of("42"), result.getScopeRefIds("COMPANY"));
        assertTrue(result.isSuperAdmin(), "super-admin flag must be preserved");
    }

    @Test
    void scopedUser_allowedCompany_narrowedAndOtherScopesPreserved() {
        AuthzMeResponse authz = buildAuthz(false, List.of(
                new ScopeSummaryDto("COMPANY", "1"),
                new ScopeSummaryDto("COMPANY", "5"),
                new ScopeSummaryDto("COMPANY", "7"),
                new ScopeSummaryDto("PROJECT", "100"),
                new ScopeSummaryDto("WAREHOUSE", "9")
        ));

        AuthzMeResponse result = narrower.narrow(authz, "5");

        assertEquals(Set.of("5"), result.getScopeRefIds("COMPANY"),
                "COMPANY scope must be narrowed to exactly the picker selection");
        assertEquals(Set.of("100"), result.getScopeRefIds("PROJECT"),
                "PROJECT scope must pass through untouched");
        assertEquals(Set.of("9"), result.getScopeRefIds("WAREHOUSE"),
                "WAREHOUSE scope must pass through untouched");
    }

    @Test
    void scopedUser_disallowedCompany_throws403() {
        AuthzMeResponse authz = buildAuthz(false, List.of(
                new ScopeSummaryDto("COMPANY", "1"),
                new ScopeSummaryDto("COMPANY", "5")));

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> narrower.narrow(authz, "99"));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode(),
                "users must not be able to widen their scope via the picker header");
    }

    @Test
    void nonNumericHeader_throws400() {
        AuthzMeResponse authz = buildAuthz(true, List.of());

        ResponseStatusException ex = assertThrows(
                ResponseStatusException.class,
                () -> narrower.narrow(authz, "not-a-number"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void nullAuthz_passesThroughAsNull() {
        // Defensive: callers shouldn't pass null authz (Spring Security
        // prevents anonymous), but if they do, don't NPE.
        assertNull(narrower.narrow(null, "5"));
    }
}
