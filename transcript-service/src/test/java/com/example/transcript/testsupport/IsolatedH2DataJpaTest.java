package com.example.transcript.testsupport;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.UUID;

import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

/**
 * Module-wide replacement for the
 * {@code @DataJpaTest + @AutoConfigureTestDatabase(replace=NONE) + @ActiveProfiles("test")}
 * trio used by every H2 {@code @DataJpaTest} class in {@code transcript-service}.
 * Pins each distinct Spring context boot under this annotation to its own
 * UUID-named in-memory H2 instance via an {@link ApplicationContextInitializer}
 * declared on {@link ContextConfiguration#initializers()}, so no sibling class's
 * {@code ddl-auto=create-drop} can drop this class's schema regardless of
 * execution order or context-cache eviction.
 *
 * <p>Verbatim port of {@code endpoint-admin-service}'s
 * {@code com.example.endpointadmin.testsupport.IsolatedH2DataJpaTest} (only the
 * package + URL prefix differ). Postgres Testcontainers integration tests
 * ({@code *PostgresIntegrationTest}) MUST keep using {@code @DataJpaTest}
 * directly with their own {@code @DynamicPropertySource} — applying this
 * annotation to them would override the PG URL with an H2 URL.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@ContextConfiguration(initializers = IsolatedH2DataJpaTest.IsolatedH2Initializer.class)
public @interface IsolatedH2DataJpaTest {

    class IsolatedH2Initializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

        @Override
        public void initialize(ConfigurableApplicationContext applicationContext) {
            String dbName = "transcript-it-" + UUID.randomUUID();
            String url = "jdbc:h2:mem:" + dbName
                    + ";MODE=PostgreSQL"
                    + ";DATABASE_TO_LOWER=TRUE"
                    + ";DEFAULT_NULL_ORDERING=HIGH"
                    + ";DB_CLOSE_DELAY=-1"
                    + ";DB_CLOSE_ON_EXIT=FALSE";
            TestPropertyValues.of("spring.datasource.url=" + url).applyTo(applicationContext);
        }
    }
}
