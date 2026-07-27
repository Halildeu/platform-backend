package com.example.permission;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * MVT-1: Spring Boot context smoke test.
 * Catches: missing @Autowired, broken bean wiring, invalid @ConditionalOnProperty.
 *
 * TB-16: H2 mode fails due to Vault/Eureka/WebClient beans requiring real infra.
 * This test requires Testcontainers PostgreSQL (mvn -P testcontainers test).
 * Disabled in default surefire run — enabled in CI integration profile.
 */
// #933: this class used to carry
//   @Disabled("TB-16: requires Testcontainers PostgreSQL. Run with: mvn -P testcontainers ...")
// That diagnosis was wrong. The context did not fail on the database — it failed on bean
// wiring: with erp.openfga.enabled=false, OpenFgaAuthzConfig (the whole @Configuration)
// vanished, so WebMvcConfig could not get OpenFgaAuthzService and the context died. Once
// the authz-service bean was made unconditional, this test passes on plain H2 with no
// Testcontainers at all. The @Disabled had been hiding the defect it was named after.
@Tag("integration")
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
                "erp.openfga.enabled=false",
                "spring.task.execution.pool.core-size=1"
        }
)
class HaloApplicationTests {

    @Test
    void contextLoads_openfgaDisabled() {
        // If this test passes, all non-OpenFGA beans wire correctly.
        // Catches: missing @Autowired, circular deps, broken constructor injection.
    }
}
