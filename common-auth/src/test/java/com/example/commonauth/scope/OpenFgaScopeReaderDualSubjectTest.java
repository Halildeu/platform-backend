package com.example.commonauth.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * board #2531 — dual-subject scope read.
 *
 * <p>Two writers store tuples for the SAME verified principal under different subject forms:
 * {@code TupleSyncService} → {@code user:<numeric>}, canonical {@code POST /api/v1/access/scope}
 * → {@code user:<KC sub>}. Reading only one form made the other writer's grants invisible
 * (API said 201, user got 403).
 */
class OpenFgaScopeReaderDualSubjectTest {

    private static final String NUMERIC = "1204";
    private static final String SUB = "6f49871e-aaaa-bbbb-cccc-000000000001";

    private OpenFgaAuthzService authz;
    private OpenFgaProperties props;

    @BeforeEach
    void setUp() {
        authz = mock(OpenFgaAuthzService.class);
        props = new OpenFgaProperties();
        props.setEnabled(true);
        // no tuples anywhere by default
        when(authz.listObjectIds(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(Set.of());
        when(authz.check(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString())).thenReturn(false);
    }

    @Test
    @DisplayName("grant written under the KC sub is visible when the alias is supplied")
    void aliasGrantIsVisible() {
        // canonical /access/scope path wrote user:<sub> viewer project:wc-project-1204
        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of(1204L));

        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);
        Map<String, Set<Long>> summary = reader.readScopeSummary(NUMERIC, SUB);

        assertEquals(Set.of(1204L), summary.get("PROJECT"),
                "scope granted under the sub subject must surface in the summary");
    }

    @Test
    @DisplayName("without the alias the same grant stays invisible (regression witness)")
    void withoutAliasGrantIsLost() {
        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of(1204L));

        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);
        Map<String, Set<Long>> summary = reader.readScopeSummary(NUMERIC);

        assertTrue(summary.isEmpty(),
                "single-subject read cannot see the other writer's tuples — this is the bug #2531 fixes");
    }

    @Test
    @DisplayName("union: numeric and sub tuples are merged, not replaced")
    void unionOfBothSubjects() {
        when(authz.listObjectIds(eq(NUMERIC), eq("viewer"), eq("project"))).thenReturn(Set.of(9L));
        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of(1204L));

        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);
        Map<String, Set<Long>> summary = reader.readScopeSummary(NUMERIC, SUB);

        assertEquals(Set.of(9L, 1204L), summary.get("PROJECT"),
                "both writers' tuples for the same principal must be unioned");
    }

    @Test
    @DisplayName("superAdmin is true when either verified subject carries the admin tuple")
    void superAdminViaAlias() {
        when(authz.check(eq(SUB), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);
        ScopeContext ctx = reader.readScopeContext(NUMERIC, SUB);

        assertTrue(ctx.superAdmin(), "admin tuple stored under the sub subject must still be honoured");
    }

    @Test
    @DisplayName("blank / identical alias does not trigger a second lookup")
    void noRedundantLookup() {
        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);

        reader.readScopeContext(NUMERIC, NUMERIC);   // same id
        reader.readScopeContext(NUMERIC, "   ");     // blank
        reader.readScopeContext(NUMERIC, null);      // absent

        verify(authz, never()).listObjectIds(eq("   "), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    @DisplayName("cache key keeps '<userId>:' prefix so evictUser() still invalidates alias reads")
    void aliasCacheKeyStaysEvictable() {
        // Guards the trap: a '|' separator would produce "1204|<sub>:v1..." which
        // ScopeContextCache.evictUser("1204") (prefix "1204:") would NOT remove — the user would
        // keep a stale empty scope right after a grant.
        String key = ScopeContextCache.cacheKey(NUMERIC + ":" + SUB, 1L, "store", "model");
        assertTrue(key.startsWith(NUMERIC + ":"),
                "alias cache key must remain matchable by evictUser's \"<userId>:\" prefix");
    }
}
