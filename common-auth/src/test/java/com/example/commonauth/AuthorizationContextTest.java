package com.example.commonauth;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for AuthorizationContext — permission checks, scope access, edge cases.
 * SK-7: coverage target >= 80%.
 */
class AuthorizationContextTest {

    @Test
    void of_withNullSets_createsEmptySets() {
        var ctx = AuthorizationContext.of(1L, "test@test.com", null, null);
        assertNotNull(ctx.getRoles());
        assertNotNull(ctx.getPermissions());
        assertTrue(ctx.getRoles().isEmpty());
        assertTrue(ctx.getPermissions().isEmpty());
    }

    @Test
    void of_withFullScope_preservesAllFields() {
        var ctx = AuthorizationContext.of(1L, "a@b.com",
                Set.of("ADMIN"), Set.of("READ"),
                Set.of(10L), Set.of(20L), Set.of(30L));
        assertEquals(1L, ctx.getUserId());
        assertEquals("a@b.com", ctx.getEmail());
        assertEquals(Set.of("ADMIN"), ctx.getRoles());
        assertEquals(Set.of("READ"), ctx.getPermissions());
        assertEquals(Set.of(10L), ctx.getAllowedCompanyIds());
        assertEquals(Set.of(20L), ctx.getAllowedProjectIds());
        assertEquals(Set.of(30L), ctx.getAllowedWarehouseIds());
    }

    @Test
    void isAdmin_withAdminRole_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of("admin"), Set.of());
        assertTrue(ctx.isAdmin());
    }

    @Test
    void isAdmin_withAdminPermission_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("ADMIN"));
        assertTrue(ctx.isAdmin());
    }

    @Test
    void isAdmin_withNoAdmin_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of("USER"), Set.of("READ"));
        assertFalse(ctx.isAdmin());
    }

    @Test
    void hasPermission_existingPermission_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("REPORT_VIEW"));
        assertTrue(ctx.hasPermission("REPORT_VIEW"));
    }

    @Test
    void hasPermission_sameCase_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("report_view"));
        assertTrue(ctx.hasPermission("report_view"));
    }

    @Test
    void hasPermission_missingPermission_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("READ"));
        assertFalse(ctx.hasPermission("WRITE"));
    }

    @Test
    void hasPermission_null_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("READ"));
        assertFalse(ctx.hasPermission(null));
    }

    @Test
    void hasPermission_blank_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of("READ"));
        assertFalse(ctx.hasPermission("  "));
    }

    @Test
    void canAccessCompany_allowed_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of(),
                Set.of(10L, 20L), Set.of(), Set.of());
        assertTrue(ctx.canAccessCompany(10L));
    }

    @Test
    void canAccessCompany_notAllowed_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of(),
                Set.of(10L), Set.of(), Set.of());
        assertFalse(ctx.canAccessCompany(99L));
    }

    @Test
    void canAccessCompany_null_returnsFalse() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of(),
                Set.of(10L), Set.of(), Set.of());
        assertFalse(ctx.canAccessCompany(null));
    }

    @Test
    void canAccessProject_allowed_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of(),
                Set.of(), Set.of(5L), Set.of());
        assertTrue(ctx.canAccessProject(5L));
    }

    @Test
    void canAccessWarehouse_allowed_returnsTrue() {
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of(),
                Set.of(), Set.of(), Set.of(7L));
        assertTrue(ctx.canAccessWarehouse(7L));
    }

    @Test
    void setsAreImmutable() {
        var ctx = AuthorizationContext.of(1L, "a@b.com",
                Set.of("A"), Set.of("B"),
                Set.of(1L), Set.of(2L), Set.of(3L));
        assertThrows(UnsupportedOperationException.class, () -> ctx.getRoles().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.getPermissions().add("X"));
        assertThrows(UnsupportedOperationException.class, () -> ctx.getAllowedCompanyIds().add(99L));
    }
}
