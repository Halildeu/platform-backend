package com.example.commonauth;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class AuthenticatedUserLookupServiceTest {

    @Test
    void resolve_prefersNumericUidClaim() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), null),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "uid", 42L,
                "email", "admin@example.com"
        ), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_fallsBackToEmailLookupWhenSubjectIsNotNumeric() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(Map.of("id", 7L)), "user_service.users"),
                "user_service.users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertEquals(7L, resolved.numericUserId());
        assertEquals("7", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_returnsSubjectWhenLookupCannotResolveNumericUserId() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(new StubJdbcTemplate(List.of(), null), "users");
        Jwt jwt = buildJwt(Map.of(
                "preferred_username", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_skipsSqlLookupWhenConfiguredTableDoesNotExist() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), null),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_returnsNullWhenTableProbeFails() {
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new FailingProbeJdbcTemplate(),
                "users"
        );
        Jwt jwt = buildJwt(Map.of(
                "email", "admin@example.com"
        ), "7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("7d31b1a8-0f4d-43d8-a5df-d7cfbb5304f4", resolved.responseUserId());
        assertEquals("admin@example.com", resolved.email());
    }

    @Test
    void resolve_softDeleteColumn_suppressesTombstonedNumericClaim() {
        // Finding 1 (Codex thread 019ea6f6 REVISE): with a soft-delete column
        // configured, a numeric uid/userId/sub claim pointing at a tombstone
        // must NOT resolve — neither numericUserId nor responseUserId may echo
        // the deleted id. Active-by-id query returns empty = tombstone.
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(), "user_service.users"),
                "user_service.users",
                "deleted_at"
        );
        Jwt jwt = buildJwt(Map.of("uid", 42L, "email", "ghost@example.com"), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertNull(resolved.responseUserId());
        assertEquals("ghost@example.com", resolved.email());
    }

    @Test
    void resolve_softDeleteColumn_keepsActiveNumericClaim() {
        // An active numeric id is preserved when the soft-delete column is set.
        AuthenticatedUserLookupService service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(Map.of("id", 42L)), "user_service.users"),
                "user_service.users",
                "deleted_at"
        );
        Jwt jwt = buildJwt(Map.of("uid", 42L, "email", "live@example.com"), "kc-user-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
    }

    // ── Slice 2 (Codex 019ef349): kc_subject cross-check (hardened path) ──────

    @Test
    void hardened_claimMatchingSubject_isAccepted() {
        // claim userId=42; the row at id=42 is owned by THIS token's sub → trust.
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .crossCheck(List.of(Map.of("kc_sub", "kc-uuid-alice", "email", "alice@example.com")));
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("userId", 42L, "email", "alice@example.com"), "kc-uuid-alice");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
        assertEquals("42", resolved.responseUserId());
    }

    @Test
    void hardened_claimMatchingEmailButDifferentSubject_isAccepted() {
        // sub differs from the row's kc_subject, but the canonical email matches
        // (case-insensitive) → still the same identity → trust the claim id.
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .crossCheck(List.of(Map.of("kc_sub", "row-uuid", "email", "alice@example.com")));
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("userId", 42L, "email", "ALICE@example.com"), "token-uuid-alice");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
    }

    @Test
    void hardened_claimMismatch_resolvesTokenIdentityNotForeignId() {
        // THE anti-cross-user invariant: token carries userId=999 (a foreign /
        // stale id whose row belongs to the VICTIM), but its real identity is
        // the attacker (sub=attacker-uuid). The 999 row does not match the token
        // → discard 999 → resolve by kc_subject to the attacker's OWN id (7).
        // The foreign victim id 999 must never surface.
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .crossCheck(List.of(Map.of("kc_sub", "victim-uuid", "email", "victim@example.com")))
                .kcSubjectId(List.of(Map.of("id", 7L)));
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("userId", 999L, "email", "attacker@example.com"), "attacker-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(7L, resolved.numericUserId());
        assertEquals("7", resolved.responseUserId());
        assertNotEquals("999", resolved.responseUserId());
    }

    @Test
    void hardened_claimMismatch_noIdentityResolution_neverEchoesForeignId() {
        // Mismatched claim AND no kc_subject/email resolution → numericUserId is
        // null and responseUserId falls back to the token subject, NEVER the
        // discarded foreign claim id.
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .crossCheck(List.of(Map.of("kc_sub", "victim-uuid", "email", "victim@example.com")))
                .kcSubjectId(List.of())
                .emailId(List.of());
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("userId", 999L, "email", "ghost@example.com"), "ghost-uuid");

        var resolved = service.resolve(jwt);

        assertNull(resolved.numericUserId());
        assertEquals("ghost-uuid", resolved.responseUserId());
        assertNotEquals("999", resolved.responseUserId());
    }

    @Test
    void hardened_claimRowAbsentOrTombstoned_discardsAndResolvesByEmail() {
        // The id=42 row is absent (or soft-deleted → filtered) → cross-check
        // fails → discard 42 → resolve by the token's email instead (id 5).
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .crossCheck(List.of())
                .kcSubjectId(List.of())
                .emailId(List.of(Map.of("id", 5L)));
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("userId", 42L, "email", "real@example.com"), "real-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(5L, resolved.numericUserId());
        assertEquals("5", resolved.responseUserId());
    }

    @Test
    void hardened_noClaim_resolvesByKcSubjectFirst() {
        // No numeric claim (pure M365 KC token, sub=UUID) → resolve by
        // kc_subject (strongest identity) ahead of email.
        var stub = new SqlAwareJdbcTemplate("user_service.users")
                .kcSubjectId(List.of(Map.of("id", 11L)))
                .emailId(List.of(Map.of("id", 99L)));
        var service = new AuthenticatedUserLookupService(stub, "user_service.users", "deleted_at", "kc_subject");
        Jwt jwt = buildJwt(Map.of("email", "alice@example.com"), "kc-uuid-alice");

        var resolved = service.resolve(jwt);

        assertEquals(11L, resolved.numericUserId());
    }

    @Test
    void legacy_withoutKcSubjectColumn_stillTrustsActiveClaim() {
        // Regression: a consumer that has NOT opted into the cross-check (3-arg
        // ctor) keeps the legacy claim-first behaviour unchanged.
        var service = new AuthenticatedUserLookupService(
                new StubJdbcTemplate(List.of(Map.of("id", 42L)), "user_service.users"),
                "user_service.users", "deleted_at");
        Jwt jwt = buildJwt(Map.of("userId", 42L, "email", "legacy@example.com"), "kc-uuid");

        var resolved = service.resolve(jwt);

        assertEquals(42L, resolved.numericUserId());
    }

    private static Jwt buildJwt(Map<String, Object> claims, String subject) {
        var builder = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .subject(subject);
        claims.forEach(builder::claim);
        return builder.build();
    }

    private static final class StubJdbcTemplate extends JdbcTemplate {
        private final List<Map<String, Object>> rows;
        private final String relationName;

        private StubJdbcTemplate(List<Map<String, Object>> rows, String relationName) {
            this.rows = rows;
            this.relationName = relationName;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(relationName);
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            return rows;
        }
    }

    private static final class FailingProbeJdbcTemplate extends JdbcTemplate {
        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            throw new DataAccessResourceFailureException("probe failed");
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            throw new AssertionError("queryForList should not be called when table probe fails");
        }
    }

    /**
     * SQL-aware stub for the hardened (kc_subject cross-check) path, where one
     * resolve() makes up to three DISTINCT queries that must return different
     * rows: the by-id cross-check (`... as kc_sub ...`), the kc_subject lookup
     * (`where kc_subject = ?`) and the email lookup (`lower(email) = ?`).
     */
    private static final class SqlAwareJdbcTemplate extends JdbcTemplate {
        private final String relationName;
        private List<Map<String, Object>> crossCheckRows = List.of();
        private List<Map<String, Object>> kcSubjectRows = List.of();
        private List<Map<String, Object>> emailRows = List.of();

        private SqlAwareJdbcTemplate(String relationName) {
            this.relationName = relationName;
        }

        private SqlAwareJdbcTemplate crossCheck(List<Map<String, Object>> rows) {
            this.crossCheckRows = rows;
            return this;
        }

        private SqlAwareJdbcTemplate kcSubjectId(List<Map<String, Object>> rows) {
            this.kcSubjectRows = rows;
            return this;
        }

        private SqlAwareJdbcTemplate emailId(List<Map<String, Object>> rows) {
            this.emailRows = rows;
            return this;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            return requiredType.cast(relationName); // to_regclass capability probe
        }

        @Override
        public List<Map<String, Object>> queryForList(String sql, Object... args) {
            if (sql.contains("as kc_sub")) {
                return crossCheckRows;
            }
            if (sql.contains("kc_subject = ?")) {
                return kcSubjectRows;
            }
            if (sql.contains("lower(email)")) {
                return emailRows;
            }
            return List.of();
        }
    }
}
