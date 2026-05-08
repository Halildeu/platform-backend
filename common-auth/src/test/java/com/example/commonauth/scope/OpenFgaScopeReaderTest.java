package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3):
 * unit tests for {@link OpenFgaScopeReader} — relation alignment,
 * cache integration, parallel fetch, superAdmin bypass, and the
 * exception contract that lets {@link ScopeContextFilter} fall back
 * to dev scope while admin-side callers use the safe variant.
 */
@ExtendWith(MockitoExtension.class)
class OpenFgaScopeReaderTest {

    @Mock OpenFgaAuthzService authzService;

    private OpenFgaProperties enabledProps() {
        var props = new OpenFgaProperties();
        props.setEnabled(true);
        props.setStoreId("store-1");
        props.setModelId("model-1");
        return props;
    }

    private OpenFgaProperties disabledProps() {
        var props = new OpenFgaProperties();
        props.setEnabled(false);
        return props;
    }

    @Test
    @DisplayName("readScopeContext: 'viewer' relation for ALL scope types (Faz 21.3 ADR-0008)")
    void allObjectTypesUseViewerRelation() {
        when(authzService.listObjectIds("1204", "viewer", "company")).thenReturn(Set.of(38L, 39L));
        when(authzService.listObjectIds("1204", "viewer", "project")).thenReturn(Set.of(100L));
        when(authzService.listObjectIds("1204", "viewer", "warehouse")).thenReturn(Set.of(5L));
        when(authzService.listObjectIds("1204", "viewer", "branch")).thenReturn(Set.of(7L));
        when(authzService.check("1204", "admin", "organization", "default")).thenReturn(false);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        ScopeContext ctx = reader.readScopeContext("1204");

        assertEquals("1204", ctx.userId());
        assertTrue(ctx.allowedCompanyIds().contains(38L));
        assertTrue(ctx.allowedCompanyIds().contains(39L));
        assertTrue(ctx.allowedProjectIds().contains(100L));
        assertTrue(ctx.allowedWarehouseIds().contains(5L));
        assertTrue(ctx.allowedBranchIds().contains(7L));
        assertFalse(ctx.superAdmin());

        // Regression guard: reader must use 'viewer' (not 'operator' or 'member')
        verify(authzService).listObjectIds("1204", "viewer", "warehouse");
        verify(authzService).listObjectIds("1204", "viewer", "branch");
    }

    @Test
    @DisplayName("readScopeContext: superAdmin with persisted tuples → flag=true AND allowed sets populated")
    void superAdminWithExplicitAssignments() {
        // PR-BE-14 (Codex thread 019e0938 iter-7 AGREE absorb, 2026-05-08):
        // pre-fix returned ScopeContext.superAdmin() (allowed sets EMPTY)
        // — discarding the persisted OpenFGA tuples that the parallel
        // futures had already fetched. Post-fix: allowed*Ids reflect
        // the actual stored assignments. The superAdmin flag still
        // takes precedence at canAccessX() predicates (separate test
        // below) so the bypass guarantee is preserved; the scope
        // summary's role narrows to "explicit assignments / admin
        // observability".
        when(authzService.listObjectIds("admin-1", "viewer", "company")).thenReturn(Set.of(12L, 21L, 35L));
        when(authzService.listObjectIds("admin-1", "viewer", "project")).thenReturn(Set.of(100L));
        when(authzService.listObjectIds("admin-1", "viewer", "warehouse")).thenReturn(Set.of());
        when(authzService.listObjectIds("admin-1", "viewer", "branch")).thenReturn(Set.of());
        when(authzService.check("admin-1", "admin", "organization", "default")).thenReturn(true);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        ScopeContext ctx = reader.readScopeContext("admin-1");

        assertTrue(ctx.superAdmin(), "superAdmin flag must remain true");
        assertEquals(3, ctx.allowedCompanyIds().size(), "persisted company tuples must be reported");
        assertTrue(ctx.allowedCompanyIds().contains(12L));
        assertTrue(ctx.allowedCompanyIds().contains(21L));
        assertTrue(ctx.allowedCompanyIds().contains(35L));
        assertEquals(1, ctx.allowedProjectIds().size());
    }

    @Test
    @DisplayName("readScopeContext: superAdmin with no persisted tuples → flag=true AND allowed sets empty")
    void superAdminWithoutAssignments() {
        // The legitimate-empty case: superAdmin user that was never
        // assigned explicit OpenFGA scopes. Both the flag stays true
        // AND the sets stay empty — matches the genuine "no scope
        // assigned yet" view.
        when(authzService.listObjectIds(anyString(), anyString(), anyString())).thenReturn(Set.of());
        when(authzService.check("admin-fresh", "admin", "organization", "default")).thenReturn(true);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        ScopeContext ctx = reader.readScopeContext("admin-fresh");

        assertTrue(ctx.superAdmin());
        assertTrue(ctx.allowedCompanyIds().isEmpty());
        assertTrue(ctx.allowedProjectIds().isEmpty());
    }

    @Test
    @DisplayName("readScopeContext: superAdmin canAccessX() predicates still bypass even with empty sets")
    void superAdminBypassRegressionGuard() {
        // PR-BE-14 regression guard: even though the allowed*Ids sets
        // can now be populated for superAdmin, the canAccessX()
        // predicates MUST short-circuit on the superAdmin flag.
        // Important property: a superAdmin with NO explicit company
        // tuple still passes canAccessCompany(99) — that's the
        // semantic separation we're protecting.
        when(authzService.listObjectIds(anyString(), anyString(), anyString())).thenReturn(Set.of());
        when(authzService.check("admin-1", "admin", "organization", "default")).thenReturn(true);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        ScopeContext ctx = reader.readScopeContext("admin-1");

        assertTrue(ctx.canAccessCompany(99L), "superAdmin must bypass company access");
        assertTrue(ctx.canAccessProject(99L), "superAdmin must bypass project access");
        assertTrue(ctx.canAccessWarehouse(99L), "superAdmin must bypass warehouse access");
        assertTrue(ctx.canAccessBranch(99L), "superAdmin must bypass branch access");
    }

    @Test
    @DisplayName("readScopeContext: null/blank userId → empty(null)")
    void blankUserIdReturnsEmpty() {
        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        assertTrue(reader.readScopeContext(null).allowedCompanyIds().isEmpty());
        assertTrue(reader.readScopeContext("").allowedCompanyIds().isEmpty());
    }

    @Test
    @DisplayName("readScopeContext: properties.enabled=false → returns empty without OpenFGA call")
    void disabledPropertiesReturnsEmptyWithoutCall() {
        var reader = new OpenFgaScopeReader(authzService, disabledProps());
        ScopeContext ctx = reader.readScopeContext("1204");

        assertEquals("1204", ctx.userId());
        assertTrue(ctx.allowedCompanyIds().isEmpty());
        verify(authzService, times(0)).listObjectIds(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("readScopeContext: OpenFGA failure propagates as RuntimeException (filter wraps)")
    void openFgaFailurePropagatesException() {
        when(authzService.listObjectIds(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("OpenFGA down"));
        when(authzService.check(anyString(), anyString(), anyString(), anyString())).thenReturn(false);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        assertThrows(Exception.class, () -> reader.readScopeContext("1204"));
    }

    @Test
    @DisplayName("readScopeSummarySafe: OpenFGA failure → empty map (admin-safe variant)")
    void safeVariantReturnsEmptyOnFailure() {
        when(authzService.listObjectIds(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("OpenFGA down"));
        when(authzService.check(anyString(), anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("OpenFGA down"));

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        Map<String, Set<Long>> summary = reader.readScopeSummarySafe("1204");

        assertTrue(summary.isEmpty());
    }

    @Test
    @DisplayName("readScopeSummary: superAdmin returns persisted tuples (PR-BE-14, was empty pre-fix)")
    void superAdminSummaryReturnsPersistedTuples() {
        // PR-BE-14 (Codex thread 019e0938 iter-7 AGREE absorb,
        // 2026-05-08): pre-fix this case returned Map.isEmpty()=true
        // (legacy DB compat). Post-fix the summary reports the
        // explicit assignments — same shape as a non-superAdmin user.
        // Admin observability + UserDetailDrawer reload UX restored.
        when(authzService.listObjectIds("admin-1", "viewer", "company")).thenReturn(Set.of(12L, 35L));
        when(authzService.listObjectIds("admin-1", "viewer", "project")).thenReturn(Set.of(99L));
        when(authzService.listObjectIds("admin-1", "viewer", "warehouse")).thenReturn(Set.of());
        when(authzService.listObjectIds("admin-1", "viewer", "branch")).thenReturn(Set.of());
        when(authzService.check("admin-1", "admin", "organization", "default")).thenReturn(true);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        Map<String, Set<Long>> summary = reader.readScopeSummary("admin-1");

        assertEquals(2, summary.size(), "company + project keys, no empty-set keys included");
        assertTrue(summary.containsKey("COMPANY"));
        assertTrue(summary.get("COMPANY").contains(12L));
        assertTrue(summary.get("COMPANY").contains(35L));
        assertTrue(summary.containsKey("PROJECT"));
        assertTrue(summary.get("PROJECT").contains(99L));
        assertFalse(summary.containsKey("WAREHOUSE"));
        assertFalse(summary.containsKey("BRANCH"));
    }

    @Test
    @DisplayName("readScopeSummary: superAdmin with no persisted tuples → empty map (legitimate-empty case)")
    void superAdminSummaryEmptyWhenNoTuples() {
        // The legitimate-empty case is preserved. The reader returns
        // the same shape (no keys) when there are no stored
        // assignments, regardless of the superAdmin flag.
        when(authzService.listObjectIds(anyString(), anyString(), anyString())).thenReturn(Set.of());
        when(authzService.check("admin-fresh", "admin", "organization", "default")).thenReturn(true);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        Map<String, Set<Long>> summary = reader.readScopeSummary("admin-fresh");

        assertTrue(summary.isEmpty());
    }

    @Test
    @DisplayName("readScopeSummary: scope-type keys upper-case (COMPANY/PROJECT/WAREHOUSE/BRANCH)")
    void summaryKeysUpperCase() {
        when(authzService.listObjectIds("1204", "viewer", "company")).thenReturn(Set.of(38L));
        when(authzService.listObjectIds("1204", "viewer", "project")).thenReturn(Set.of(100L));
        when(authzService.listObjectIds("1204", "viewer", "warehouse")).thenReturn(Set.of(5L));
        when(authzService.listObjectIds("1204", "viewer", "branch")).thenReturn(Set.of(7L));
        when(authzService.check("1204", "admin", "organization", "default")).thenReturn(false);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        Map<String, Set<Long>> summary = reader.readScopeSummary("1204");

        assertTrue(summary.containsKey("COMPANY"));
        assertTrue(summary.containsKey("PROJECT"));
        assertTrue(summary.containsKey("WAREHOUSE"));
        assertTrue(summary.containsKey("BRANCH"));
        assertTrue(summary.get("COMPANY").contains(38L));
    }

    @Test
    @DisplayName("readScopeSummary: skips empty scope types from output map")
    void summarySkipsEmptyTypes() {
        when(authzService.listObjectIds("1204", "viewer", "company")).thenReturn(Set.of(38L));
        when(authzService.listObjectIds("1204", "viewer", "project")).thenReturn(Set.of());
        when(authzService.listObjectIds("1204", "viewer", "warehouse")).thenReturn(Set.of());
        when(authzService.listObjectIds("1204", "viewer", "branch")).thenReturn(Set.of());
        when(authzService.check("1204", "admin", "organization", "default")).thenReturn(false);

        var reader = new OpenFgaScopeReader(authzService, enabledProps());
        Map<String, Set<Long>> summary = reader.readScopeSummary("1204");

        assertEquals(1, summary.size());
        assertTrue(summary.containsKey("COMPANY"));
    }

    @Test
    @DisplayName("Cache hit returns cached context, skips OpenFGA")
    void cacheHitSkipsOpenFga() {
        var cache = new ScopeContextCache(Duration.ofSeconds(30), Duration.ZERO, 100, true);
        AuthzVersionProvider vp = () -> 5L;
        ScopeContext cached = new ScopeContext("1204", Set.of(99L), Set.of(), Set.of(), Set.of(), false);
        cache.put(ScopeContextCache.cacheKey("1204", 5L, "store-1", "model-1"), cached);

        var reader = new OpenFgaScopeReader(authzService, enabledProps(), cache, vp);
        ScopeContext result = reader.readScopeContext("1204");

        assertTrue(result.allowedCompanyIds().contains(99L));
        verify(authzService, times(0)).listObjectIds(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("Cache miss fetches and populates cache")
    void cacheMissFetchesAndPopulates() {
        when(authzService.listObjectIds("1204", "viewer", "company")).thenReturn(Set.of(38L));
        when(authzService.listObjectIds("1204", "viewer", "project")).thenReturn(Set.of());
        when(authzService.listObjectIds("1204", "viewer", "warehouse")).thenReturn(Set.of());
        when(authzService.listObjectIds("1204", "viewer", "branch")).thenReturn(Set.of());
        when(authzService.check("1204", "admin", "organization", "default")).thenReturn(false);

        var cache = new ScopeContextCache(Duration.ofSeconds(30), Duration.ZERO, 100, true);
        AuthzVersionProvider vp = () -> 1L;

        var reader = new OpenFgaScopeReader(authzService, enabledProps(), cache, vp);
        reader.readScopeContext("1204");

        String key = ScopeContextCache.cacheKey("1204", 1L, "store-1", "model-1");
        assertNotNull(cache.get(key));
        assertTrue(cache.get(key).allowedCompanyIds().contains(38L));
    }
}
