package com.example.commonauth.scope;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    @DisplayName("superAdmin is computed from the PRIMARY subject only — alias is not admin-checked")
    void superAdminIsNotAliasChecked() {
        // Deliberate narrow surface (Codex, board #2531): the only production dual-subject caller
        // is the /authz/me scope summary, which does not consume ScopeContext.superAdmin — the
        // controller derives superAdmin from the verified numeric identity. Alias-checking here
        // would cost an admin lookup per summary miss and widen this shared API's semantics.
        when(authz.check(eq(SUB), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);
        when(authz.check(eq(NUMERIC), eq("admin"), eq("organization"), eq("default"))).thenReturn(false);

        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props);
        ScopeContext ctx = reader.readScopeContext(NUMERIC, SUB);

        assertFalse(ctx.superAdmin(), "alias must not grant superAdmin");
        verify(authz, never()).check(eq(SUB), eq("admin"), eq("organization"), eq("default"));
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
    @DisplayName("REVOKE: global version bump really breaks the cached ALLOW (real reader + cache)")
    void versionBumpInvalidatesCachedAliasScope() {
        // Drives the PRODUCTION reader with a real ScopeContextCache and a mutable version
        // provider — the previous version of this test hand-built a key string and asserted a
        // prefix, so it stayed green even if the reader changed its key format.
        //
        // Scenario is the dangerous direction (stale-positive privilege retention):
        //   v1: alias holds project 1204 → read caches ALLOW
        //   OpenFGA now returns nothing (tuple revoked) but version is still v1 → cached ALLOW served
        //   version bumps to v2 (OutboxPoller does this after the FGA delete) → read re-fetches → deny
        ScopeContextCache cache = new ScopeContextCache(java.time.Duration.ofMinutes(2), 100, true);
        long[] version = {1L};
        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props, cache, () -> version[0]);

        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of(1204L));
        assertEquals(Set.of(1204L), reader.readScopeSummary(NUMERIC, SUB).get("PROJECT"),
                "grant under the alias subject must be visible");

        // tuple revoked in OpenFGA, but no version bump yet
        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of());
        assertEquals(Set.of(1204L), reader.readScopeSummary(NUMERIC, SUB).get("PROJECT"),
                "still cached → proves the cache was really primed (this is the stale window)");

        // OutboxPoller bumps the global version after the FGA delete lands
        version[0] = 2L;
        assertTrue(reader.readScopeSummary(NUMERIC, SUB).isEmpty(),
                "after the version bump the revoked scope must disappear immediately — "
                        + "otherwise the user keeps revoked access for the cache TTL");
    }

    @Test
    @DisplayName("evictUser(primary) also drops alias-keyed entries (same-pod optimisation)")
    void evictUserDropsAliasKeyedEntry() {
        // Not the correctness mechanism (that is the global version bump) but the key format must
        // stay compatible with prefix eviction. Driven through the real reader, not a hand-built key.
        ScopeContextCache cache = new ScopeContextCache(java.time.Duration.ofMinutes(2), 100, true);
        OpenFgaScopeReader reader = new OpenFgaScopeReader(authz, props, cache, () -> 7L);

        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of(1204L));
        assertEquals(Set.of(1204L), reader.readScopeSummary(NUMERIC, SUB).get("PROJECT"));

        when(authz.listObjectIds(eq(SUB), eq("viewer"), eq("project"))).thenReturn(Set.of());
        cache.evictUser(NUMERIC);

        assertTrue(reader.readScopeSummary(NUMERIC, SUB).isEmpty(),
                "alias-keyed entry must be reachable by evictUser(\"<primary>\")");
    }
}
