package com.example.commonauth.scope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScopeContextTest {

    @AfterEach
    void cleanup() {
        ScopeContextHolder.clear();
    }

    @Nested
    @DisplayName("ScopeContext record")
    class RecordTests {

        @Test
        void creates_with_immutable_sets() {
            var ctx = new ScopeContext("u1", Set.of(1L, 2L), Set.of(10L), Set.of(100L), false);
            assertEquals(Set.of(1L, 2L), ctx.allowedCompanyIds());
            assertEquals(Set.of(10L), ctx.allowedProjectIds());
            assertEquals(Set.of(100L), ctx.allowedWarehouseIds());
            assertFalse(ctx.superAdmin());
        }

        @Test
        void null_sets_become_empty() {
            var ctx = new ScopeContext("u1", null, null, null, false);
            assertTrue(ctx.allowedCompanyIds().isEmpty());
            assertTrue(ctx.allowedProjectIds().isEmpty());
            assertTrue(ctx.allowedWarehouseIds().isEmpty());
        }

        @Test
        void canAccessCompany_returns_true_for_allowed() {
            var ctx = new ScopeContext("u1", Set.of(1L, 5L), Set.of(), Set.of(), false);
            assertTrue(ctx.canAccessCompany(1L));
            assertTrue(ctx.canAccessCompany(5L));
            assertFalse(ctx.canAccessCompany(99L));
        }

        @Test
        void canAccessCompany_returns_false_for_null() {
            var ctx = new ScopeContext("u1", Set.of(1L), Set.of(), Set.of(), false);
            assertFalse(ctx.canAccessCompany(null));
        }

        @Test
        void superAdmin_bypasses_all_scope_checks() {
            var ctx = ScopeContext.superAdmin("admin1");
            assertTrue(ctx.superAdmin());
            assertTrue(ctx.canAccessCompany(999L));
            assertTrue(ctx.canAccessProject(999L));
            assertTrue(ctx.canAccessWarehouse(999L));
        }

        @Test
        void empty_context_blocks_everything() {
            var ctx = ScopeContext.empty("u1");
            assertFalse(ctx.canAccessCompany(1L));
            assertFalse(ctx.canAccessProject(1L));
            assertFalse(ctx.canAccessWarehouse(1L));
            assertFalse(ctx.superAdmin());
        }
    }

    @Nested
    @DisplayName("ScopeContextHolder (ThreadLocal)")
    class HolderTests {

        @Test
        void get_returns_null_when_not_set() {
            assertNull(ScopeContextHolder.get());
        }

        @Test
        void set_and_get() {
            var ctx = new ScopeContext("u1", Set.of(1L), Set.of(), Set.of(), false);
            ScopeContextHolder.set(ctx);
            assertSame(ctx, ScopeContextHolder.get());
            assertSame(ctx, ScopeContext.current());
        }

        @Test
        void clear_removes_context() {
            ScopeContextHolder.set(ScopeContext.empty("u1"));
            ScopeContextHolder.clear();
            assertNull(ScopeContextHolder.get());
        }

        @Test
        void set_null_clears() {
            ScopeContextHolder.set(ScopeContext.empty("u1"));
            ScopeContextHolder.set(null);
            assertNull(ScopeContextHolder.get());
        }

        @Test
        void child_thread_inherits_context() throws Exception {
            var ctx = new ScopeContext("u1", Set.of(1L), Set.of(), Set.of(), false);
            ScopeContextHolder.set(ctx);

            var result = new ScopeContext[1];
            Thread child = new Thread(() -> result[0] = ScopeContextHolder.get());
            child.start();
            child.join(1000);

            assertNotNull(result[0]);
            assertEquals("u1", result[0].userId());
            assertEquals(Set.of(1L), result[0].allowedCompanyIds());
        }
    }
}
