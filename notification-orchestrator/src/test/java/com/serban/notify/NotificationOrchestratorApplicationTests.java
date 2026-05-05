package com.serban.notify;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Spring context startup smoke — Faz 23.1 Foundation D29-NOTIFY-Up'e öncül.
 * Migration apply + JPA mapping validate + bean wiring intact.
 */
@SpringBootTest
@ActiveProfiles("test")
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class NotificationOrchestratorApplicationTests extends AbstractPostgresTest {

    @Test
    void contextLoads() {
        // Spring context must boot, Flyway V1 must apply, JPA validate must
        // succeed. If any entity ↔ schema mismatch, this test fails on startup.
    }
}
