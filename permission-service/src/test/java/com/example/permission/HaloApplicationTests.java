package com.example.permission;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MVT-1: Spring Boot context smoke test.
 * Catches: missing @Autowired, broken bean wiring, invalid @ConditionalOnProperty.
 *
 * Uses H2 in PostgreSQL mode with Flyway disabled and OpenFGA disabled.
 * This ensures ALL non-conditional beans load successfully.
 */
@SpringBootTest(
        classes = PermissionServiceApplication.class,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:permtest;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
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
class HaloApplicationTests {

    @Test
    void contextLoads_openfgaDisabled() {
        // If this test passes, all non-OpenFGA beans wire correctly.
        // Catches: missing @Autowired, circular deps, broken constructor injection.
    }
}
