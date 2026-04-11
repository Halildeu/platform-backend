package com.example.permission;

import com.example.permission.event.RoleChangeEventHandler;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.AuthzVersionService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import org.junit.jupiter.api.Disabled;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TB-05: ConditionalOnProperty combination tests.
 * Verifies bean wiring with OpenFGA ON vs OFF flag combinations.
 * Uses H2 in PostgreSQL mode — no real database or OpenFGA needed.
 */
class ConditionalPropertyComboTest {

    @Nested
    @DisplayName("OpenFGA ENABLED — all Zanzibar beans must load")
    @SpringBootTest(
            classes = PermissionServiceApplication.class,
            properties = {
                    "spring.datasource.url=jdbc:h2:mem:combo_on;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "spring.sql.init.mode=never",
                    "eureka.client.enabled=false",
                    "spring.flyway.enabled=false",
                    "spring.cloud.vault.enabled=false",
                    "erp.openfga.enabled=true",
                    "erp.openfga.api-url=http://localhost:4000",
                    "erp.openfga.store-id=test-store",
                    "erp.openfga.model-id=test-model",
                    "scope.cache.enabled=false",
                    "authz.version.enabled=true"
            }
    )
    class OpenFgaEnabled {

        @Autowired
        private ApplicationContext ctx;

        @Test
        void tupleSyncService_exists() {
            assertTrue(ctx.containsBean("tupleSyncService") || ctx.getBeanNamesForType(TupleSyncService.class).length > 0,
                    "TupleSyncService must be available when erp.openfga.enabled=true");
        }

        @Test
        void authzVersionService_exists() {
            assertTrue(ctx.getBeanNamesForType(AuthzVersionService.class).length > 0,
                    "AuthzVersionService must be available when erp.openfga.enabled=true");
        }

        @Test
        void roleChangeEventHandler_exists() {
            assertTrue(ctx.getBeanNamesForType(RoleChangeEventHandler.class).length > 0,
                    "RoleChangeEventHandler must be available (AFTER_COMMIT dispatch)");
        }
    }

    @Nested
    @Disabled("TB-16: H2 context load fails — needs Testcontainers PostgreSQL")
    @DisplayName("OpenFGA DISABLED — app starts without Zanzibar beans")
    @SpringBootTest(
            classes = PermissionServiceApplication.class,
            properties = {
                    "spring.datasource.url=jdbc:h2:mem:combo_off;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                    "spring.datasource.username=sa",
                    "spring.datasource.password=",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "spring.jpa.hibernate.ddl-auto=create-drop",
                    "spring.sql.init.mode=never",
                    "eureka.client.enabled=false",
                    "spring.flyway.enabled=false",
                    "spring.cloud.vault.enabled=false",
                    "erp.openfga.enabled=false"
            }
    )
    class OpenFgaDisabled {

        @Autowired
        private ApplicationContext ctx;

        @Test
        void contextLoads() {
            assertNotNull(ctx, "Application context must load with erp.openfga.enabled=false");
        }

        @Test
        void roleChangeEventHandler_stillExists() {
            // Handler is a @Component — always loaded. It just won't receive events
            // because no service publishes RoleChangeEvent when OpenFGA is OFF.
            assertTrue(ctx.getBeanNamesForType(RoleChangeEventHandler.class).length > 0,
                    "RoleChangeEventHandler is @Component — always present");
        }
    }
}
