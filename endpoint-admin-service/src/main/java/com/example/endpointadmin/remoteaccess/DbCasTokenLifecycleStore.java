package com.example.endpointadmin.remoteaccess;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Faz 22.6 B2.2b — production {@link TokenLifecycleStore} backed by PostgreSQL (DB-CAS), behind the same
 * interface as the in-memory reference (B2.1). Atomicity comes from a single-statement
 * {@code INSERT ... ON CONFLICT DO NOTHING} (criterion #1/#8 — no lost-update race); revocation is
 * authoritative via an idempotent upsert that pre-empts a concurrent consume (criterion #3); time
 * decisions are DB-authoritative (criterion #4); a DB outage fails closed (criterion #7/#9).
 *
 * <p>Constructor-injected {@link JdbcTemplate} (not a Spring bean) so it stays disabled-by-default — the
 * runtime that wires it is {@code @ConditionalOnProperty} + D10-gated (B2.2c).
 */
public final class DbCasTokenLifecycleStore implements TokenLifecycleStore {

    /**
     * Pre-emptive-revoke retention horizon (Codex 019eb54b absorb): a revoke of an unseen jti must NOT be
     * purged early by an {@code expires_at}-based cleanup, so its row's {@code expires_at} is pinned this
     * far ahead (an audit/forensic horizon). Any TTL cleanup MUST also exclude REVOKED rows (defense in
     * depth) — the revocation record outlives the token.
     */
    private static final Duration REVOCATION_RETENTION = Duration.ofDays(90);

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * @param schema the DB schema holding {@code remote_session_token} (e.g. {@code endpoint_admin_service},
     *               from {@code ENDPOINT_ADMIN_DB_SCHEMA}). Validated to a safe identifier and used as a
     *               qualified table prefix (the JdbcTemplate connection's search_path is not assumed).
     */
    public DbCasTokenLifecycleStore(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.table = schema + ".remote_session_token";
    }

    @Override
    public ConsumeOutcome consume(String jti, Instant expiresAt, Instant now, String boundThumbprint) {
        if (jti == null || jti.isBlank() || expiresAt == null || now == null) {
            return ConsumeOutcome.INVALID;
        }
        boolean live = now.isBefore(expiresAt);
        String insertState = live ? "USED" : "EXPIRED";
        String thumbprint = CertThumbprint.isPresent(boundThumbprint) ? boundThumbprint : null;
        try {
            // Atomic: only the single winning caller inserts the row (single-use + cert-binding one write).
            int inserted = jdbc.update(
                    "INSERT INTO " + table + " (jti, state, expires_at, consumed_at, created_at, bound_cert_thumbprint) "
                            + "VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (jti) DO NOTHING",
                    jti, insertState, Timestamp.from(expiresAt),
                    live ? Timestamp.from(now) : null, Timestamp.from(now), thumbprint);
            if (inserted == 1) {
                return live ? ConsumeOutcome.ACCEPTED : ConsumeOutcome.EXPIRED;
            }
            // Conflict — a row already exists. Map its state deterministically.
            String state = currentState(jti);
            if (state == null) {
                return ConsumeOutcome.STORE_UNAVAILABLE; // row vanished mid-call → fail-closed
            }
            return switch (state) {
                case "USED" -> ConsumeOutcome.ALREADY_USED;
                case "REVOKED" -> ConsumeOutcome.REVOKED;
                case "EXPIRED" -> ConsumeOutcome.EXPIRED;
                default -> ConsumeOutcome.INVALID;
            };
        } catch (DataAccessException ex) {
            return ConsumeOutcome.STORE_UNAVAILABLE; // fail-closed (criterion #7/#9)
        }
    }

    @Override
    public MutationOutcome revoke(String jti) {
        if (jti == null || jti.isBlank()) {
            return MutationOutcome.NOOP;
        }
        try {
            // Idempotent + pre-emptive: revoke wins even for an unseen jti (insert REVOKED) and over any
            // non-revoked existing row (update). A no-op only when already REVOKED. A pre-emptive row's
            // expires_at is pinned to a revocation-retention horizon so it is not purged early.
            Instant nowTs = Instant.now();
            int changed = jdbc.update(
                    "INSERT INTO " + table + " (jti, state, expires_at, revoked_at, created_at) "
                            + "VALUES (?, 'REVOKED', ?, ?, ?) "
                            + "ON CONFLICT (jti) DO UPDATE SET state = 'REVOKED', revoked_at = EXCLUDED.revoked_at "
                            + "WHERE " + table + ".state <> 'REVOKED'",
                    jti, Timestamp.from(nowTs.plus(REVOCATION_RETENTION)),
                    Timestamp.from(nowTs), Timestamp.from(nowTs));
            return changed >= 1 ? MutationOutcome.UPDATED : MutationOutcome.NOOP;
        } catch (DataAccessException ex) {
            // A FAILED revoke must surface (Codex absorb) — the fanout retries/alerts; never silent NOOP.
            return MutationOutcome.STORE_UNAVAILABLE;
        }
    }

    @Override
    public MutationOutcome expire(String jti) {
        if (jti == null || jti.isBlank()) {
            return MutationOutcome.NOOP;
        }
        try {
            // expire does NOT override REVOKED or an existing EXPIRED.
            int changed = jdbc.update(
                    "UPDATE " + table + " SET state = 'EXPIRED' "
                            + "WHERE jti = ? AND state NOT IN ('REVOKED', 'EXPIRED')",
                    jti);
            return changed >= 1 ? MutationOutcome.UPDATED : MutationOutcome.NOOP;
        } catch (DataAccessException ex) {
            return MutationOutcome.STORE_UNAVAILABLE; // a failed expire surfaces too
        }
    }

    @Override
    public TokenLiveCheckResult isTokenLive(String jti, Instant now) {
        if (jti == null || jti.isBlank() || now == null) {
            return TokenLiveCheckResult.INVALID;
        }
        try {
            Map<String, Object> row = jdbc.queryForList(
                    "SELECT state, expires_at FROM " + table + " WHERE jti = ?", jti)
                    .stream().findFirst().orElse(null);
            if (row == null) {
                return TokenLiveCheckResult.NOT_FOUND;
            }
            String state = (String) row.get("state");
            return switch (state) {
                case "REVOKED" -> TokenLiveCheckResult.REVOKED;
                case "EXPIRED" -> TokenLiveCheckResult.EXPIRED;
                case "USED" -> {
                    Timestamp exp = (Timestamp) row.get("expires_at");
                    // DB-authoritative time decision — past-TTL reads EXPIRED without a cleanup pass.
                    yield (exp != null && now.isBefore(exp.toInstant()))
                            ? TokenLiveCheckResult.LIVE
                            : TokenLiveCheckResult.EXPIRED;
                }
                default -> TokenLiveCheckResult.INVALID;
            };
        } catch (DataAccessException ex) {
            return TokenLiveCheckResult.STORE_UNAVAILABLE; // fail-closed
        }
    }

    @Override
    public Optional<Instant> revokedAt(String jti) {
        if (jti == null || jti.isBlank()) {
            return Optional.empty();
        }
        try {
            // Only a REVOKED row carries the SLO t0; read what the DB actually recorded (source-bound).
            return jdbc.queryForList(
                    "SELECT revoked_at FROM " + table + " WHERE jti = ? AND state = 'REVOKED'", jti)
                    .stream().findFirst()
                    .map(row -> (Timestamp) row.get("revoked_at"))
                    .filter(ts -> ts != null)
                    .map(Timestamp::toInstant);
        } catch (DataAccessException ex) {
            return Optional.empty(); // store-down → no DB anchor; caller falls back to the event clock
        }
    }

    @Override
    public Optional<String> boundThumbprint(String jti) {
        if (jti == null || jti.isBlank()) {
            return Optional.empty();
        }
        try {
            return jdbc.queryForList(
                    "SELECT bound_cert_thumbprint FROM " + table + " WHERE jti = ?", jti)
                    .stream().findFirst()
                    .map(row -> (String) row.get("bound_cert_thumbprint"))
                    .filter(tp -> tp != null && !tp.isBlank());
        } catch (DataAccessException ex) {
            return Optional.empty(); // store-down → fail-closed (caller treats unknown binding as no-match)
        }
    }

    private String currentState(String jti) {
        return jdbc.queryForList("SELECT state FROM " + table + " WHERE jti = ?", String.class, jti)
                .stream().findFirst().orElse(null);
    }
}
