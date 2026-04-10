package com.example.permission;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MVT-2: Flyway migration file validation (dry-run style).
 * H2 cannot run PostgreSQL-specific SQL (TIMESTAMPTZ etc.),
 * so we validate migration file discovery and ordering instead.
 *
 * Full migration testing requires Testcontainers + PostgreSQL (future).
 */
class FlywayMigrationTest {

    @Test
    void allMigrationFiles_areDiscoveredAndOrdered() {
        Flyway flyway = Flyway.configure()
                .dataSource(
                        "jdbc:h2:mem:flyway_validate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH",
                        "sa", "")
                .locations("classpath:db/migration")
                .defaultSchema("PUBLIC")
                .schemas("PUBLIC")
                .load();

        MigrationInfo[] pending = flyway.info().pending();

        assertTrue(pending.length >= 9, "Expected at least 9 migrations, found " + pending.length);

        // Verify ordering
        for (int i = 1; i < pending.length; i++) {
            assertTrue(
                    pending[i].getVersion().compareTo(pending[i - 1].getVersion()) > 0,
                    "Migrations not in order: " + pending[i - 1].getVersion() + " >= " + pending[i].getVersion()
            );
        }

        // Verify critical migrations exist
        boolean hasV6 = false, hasV7 = false, hasV8 = false, hasV9 = false;
        for (MigrationInfo info : pending) {
            String v = info.getVersion().toString();
            if (v.equals("6")) hasV6 = true;
            if (v.equals("7")) hasV7 = true;
            if (v.equals("8")) hasV8 = true;
            if (v.equals("9")) hasV9 = true;
        }
        assertTrue(hasV6, "V6 (authz_sync_version) migration missing");
        assertTrue(hasV7, "V7 (remove page/field) migration missing");
        assertTrue(hasV8, "V8 (permission group matrix) migration missing");
        assertTrue(hasV9, "V9 (cleanup page/field rows) migration missing");
    }
}
