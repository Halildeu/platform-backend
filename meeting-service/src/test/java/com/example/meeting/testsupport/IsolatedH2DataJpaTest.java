package com.example.meeting.testsupport;

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
 * Per-class H2 isolation meta-annotation — copied verbatim from
 * endpoint-admin-service {@code IsolatedH2DataJpaTest} (BE-021 PR #319,
 * Codex 019e6fbc), retargeted to the meeting-service package + db name.
 *
 * <p>Replaces the {@code @DataJpaTest + @AutoConfigureTestDatabase(NONE) +
 * @ActiveProfiles("test")} trio. The bundled initializer assigns each
 * unique Spring context boot its own UUID-named in-memory H2 instance, so
 * no sibling class's {@code ddl-auto=create-drop} can drop this class's
 * schema regardless of execution order, Surefire fork policy, or context
 * cache eviction.
 *
 * <p>Postgres Testcontainers tests ({@code *PostgresIntegrationTest}) must
 * NOT use this annotation — they route {@code spring.datasource.url} at the
 * container via their own {@code @DynamicPropertySource}.
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
            String dbName = "meeting-it-" + UUID.randomUUID();
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
