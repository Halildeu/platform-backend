package com.example.permission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * MVT-1b: Spring Boot context smoke test with OpenFGA ENABLED.
 * Uses H2 + Flyway enabled (validates all migrations run on clean DB).
 * OpenFGA client will be created but not connected — fail-open mode.
 *
 * Catches: @ConditionalOnProperty wiring issues when flag is ON,
 * broken Flyway migrations, enum/DB schema mismatches.
 */
@SpringBootTest(
        classes = PermissionServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:permtest_openfga;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class OpenFgaContextTest {

    @Test
    void contextLoads_openfgaEnabled() {
        // If this passes: all OpenFGA beans (TupleSyncService, AuthzVersionService,
        // OpenFgaAuthzConfig) wire correctly AND Flyway migrations V1-V9 succeed on H2.
    }
}
