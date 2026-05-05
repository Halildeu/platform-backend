package com.serban.notify;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Testcontainers Postgres base — Foundation Faz 23.1 test infra.
 *
 * <p>Singleton container shared across @SpringBootTest tests; Flyway V1
 * migration applies at first context startup, JPA validate ensures entity ↔
 * schema mapping consistency.
 */
public abstract class AbstractPostgresTest {

    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"))
            .withDatabaseName("notify_test")
            .withUsername("notify_test")
            .withPassword("notify_test")
            .withReuse(true);

    static {
        POSTGRES.start();
    }

    public static class Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext ctx) {
            TestPropertyValues.of(
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.flyway.enabled=true",
                "spring.flyway.default-schema=notify",
                "spring.flyway.schemas=notify",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.properties.hibernate.default_schema=notify"
            ).applyTo(ctx.getEnvironment());
        }
    }
}
