package com.example.commonauth.openfga;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class OpenFgaAuthzServiceTest {

    @Nested
    @DisplayName("Dev mode (OpenFGA disabled)")
    class DevMode {

        private OpenFgaAuthzService createDevService() {
            var props = new OpenFgaProperties();
            props.setEnabled(false);
            props.getDevScope().setCompanyIds(Set.of(1L, 5L));
            props.getDevScope().setProjectIds(Set.of(10L, 20L));
            props.getDevScope().setWarehouseIds(Set.of(100L));
            return new OpenFgaAuthzService(null, props);
        }

        @Test
        void check_always_returns_true() {
            var svc = createDevService();
            assertTrue(svc.check("user1", "viewer", "company", "1"));
            assertTrue(svc.check("user999", "admin", "company", "999"));
        }

        @Test
        void listObjects_returns_dev_company_ids() {
            var svc = createDevService();
            List<String> ids = svc.listObjects("user1", "viewer", "company");
            assertEquals(2, ids.size());
            assertTrue(ids.contains("1"));
            assertTrue(ids.contains("5"));
        }

        @Test
        void listObjects_returns_dev_project_ids() {
            var svc = createDevService();
            List<String> ids = svc.listObjects("user1", "viewer", "project");
            assertEquals(2, ids.size());
            assertTrue(ids.contains("10"));
            assertTrue(ids.contains("20"));
        }

        @Test
        void listObjects_returns_dev_warehouse_ids() {
            var svc = createDevService();
            List<String> ids = svc.listObjects("user1", "viewer", "warehouse");
            assertEquals(1, ids.size());
            assertTrue(ids.contains("100"));
        }

        @Test
        void listObjectIds_returns_long_set() {
            var svc = createDevService();
            Set<Long> ids = svc.listObjectIds("user1", "viewer", "company");
            assertEquals(Set.of(1L, 5L), ids);
        }

        @Test
        void listObjects_unknown_type_returns_empty() {
            var svc = createDevService();
            List<String> ids = svc.listObjects("user1", "viewer", "unknown");
            assertTrue(ids.isEmpty());
        }

        @Test
        void writeTuple_does_nothing_in_dev_mode() {
            var svc = createDevService();
            assertDoesNotThrow(() -> svc.writeTuple("1", "admin", "company", "5"));
        }

        @Test
        void deleteTuple_does_nothing_in_dev_mode() {
            var svc = createDevService();
            assertDoesNotThrow(() -> svc.deleteTuple("1", "admin", "company", "5"));
        }

        @Test
        void isEnabled_returns_false() {
            var svc = createDevService();
            assertFalse(svc.isEnabled());
        }
    }

    @Nested
    @DisplayName("Default properties")
    class Defaults {

        @Test
        void default_properties_disabled() {
            var props = new OpenFgaProperties();
            assertFalse(props.isEnabled());
            assertEquals("http://localhost:4000", props.getApiUrl());
        }

        @Test
        void default_dev_scope_has_company_1() {
            var props = new OpenFgaProperties();
            assertEquals(Set.of(1L), props.getDevScope().getCompanyIds());
        }

        @Test
        void default_dev_scope_not_superadmin() {
            var props = new OpenFgaProperties();
            assertFalse(props.getDevScope().isSuperAdmin());
        }
    }
}
