package com.example.commonauth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

public class AuthenticatedUserLookupService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserLookupService.class);
    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");
    private static final Pattern COLUMN_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final JdbcTemplate jdbcTemplate;
    private final String userTable;
    private final String softDeleteColumn;
    private final String kcSubjectColumn;

    // Capability probe cache (Codex 019ef349): hasQueryableLocalUserTable()
    // is on the hot /authz/me + id-lookup path. The relation's existence is
    // process-stable, so probe once and memoize. null = not yet probed.
    private volatile Boolean tableQueryableCache;

    public AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable) {
        this(jdbcTemplate, userTable, null, null);
    }

    /**
     * @param softDeleteColumn optional soft-delete tombstone column. When
     *        non-blank, the email→userId lookup appends {@code AND <col> IS
     *        NULL} so a soft-deleted identity never resolves a numeric
     *        userId in the OpenFGA authz context (Codex 019ea573, #770
     *        Phase 2). Pass {@code null}/blank (or use the 2-arg constructor)
     *        for tables without a soft-delete column — the filter is then
     *        omitted and behaviour is unchanged (backward compatible). The
     *        value is validated as a bare SQL identifier (no injection).
     */
    public AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable, String softDeleteColumn) {
        this(jdbcTemplate, userTable, softDeleteColumn, null);
    }

    /**
     * @param kcSubjectColumn optional Keycloak-subject column (e.g.
     *        {@code kc_subject}). When non-blank, this service HARDENS the
     *        numeric {@code userId}/{@code uid}/numeric-{@code sub} claim from
     *        an AUTHORITY into a DB-verified HINT (Slice 2, Codex 019ef349):
     *        the claimed id is accepted only when the row it points to belongs
     *        to the SAME token identity ({@code kc_subject == sub} OR canonical
     *        email match). A claim that maps to a DIFFERENT (or absent /
     *        tombstoned) row is discarded and resolution falls back to the
     *        token's own {@code sub}/email — so a stale or mis-bound claim can
     *        never resolve a foreign {@code user:<id>} into the authz context.
     *        Pass {@code null}/blank (or use the 2-/3-arg constructor) to keep
     *        the legacy claim-first behaviour (backward compatible — the cross
     *        check is opt-in per consumer). Validated as a bare SQL identifier.
     */
    public AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable,
                                          String softDeleteColumn, String kcSubjectColumn) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTable = normalizeTableName(userTable);
        this.softDeleteColumn = normalizeSoftDeleteColumn(softDeleteColumn);
        this.kcSubjectColumn = normalizeColumnName(kcSubjectColumn, "kc-subject");
    }

    public ResolvedAuthenticatedUser resolve(Jwt jwt) {
        if (jwt == null) {
            return new ResolvedAuthenticatedUser(null, null, null);
        }

        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));

        Long claimUserId = firstNonNull(
                extractLongClaim(jwt, "userId"),
                extractLongClaim(jwt, "uid"),
                tryParseLong(subject)
        );

        Long numericUserId = null;
        boolean suppressedDeletedNumeric = false;
        boolean discardedClaimMismatch = false;

        if (kcSubjectColumn != null) {
            // HARDENED path (Slice 2, Codex 019ef349): the numeric claim is a
            // DB-verified HINT, not authority. Accept it only when the row it
            // points to is active AND belongs to THIS token (kc_subject == sub
            // OR canonical email match); otherwise DISCARD it and resolve by the
            // token's own verified identity (kc_subject first, then email). A
            // stale / reused / mis-bound claim therefore never resolves a
            // foreign user:<id> into the authz context.
            if (claimUserId != null) {
                if (claimIdMatchesTokenIdentity(claimUserId, subject, email)) {
                    numericUserId = claimUserId;
                } else {
                    discardedClaimMismatch = true;
                    log.warn("userid-claim cross-check failed: claimed id {} is not an active row owned by "
                            + "this token (sub/email); discarding claim, resolving by verified identity.", claimUserId);
                }
            }
            if (numericUserId == null) {
                numericUserId = lookupUserIdByKcSubject(subject);
                if (numericUserId == null) {
                    numericUserId = lookupUserIdByEmail(email);
                }
            }
        } else {
            // LEGACY path — unchanged behaviour for consumers that have NOT
            // opted into the kc_subject cross-check (Codex 019ea573, #770
            // Phase 2 soft-delete suppression preserved verbatim).
            numericUserId = claimUserId;
            if (numericUserId == null && email != null) {
                numericUserId = lookupUserIdByEmail(email);
            } else if (numericUserId != null && softDeleteColumn != null && !isActiveById(numericUserId)) {
                numericUserId = null;
                suppressedDeletedNumeric = true;
            }
        }

        String responseUserId;
        if (numericUserId != null) {
            responseUserId = Long.toString(numericUserId);
        } else if (suppressedDeletedNumeric) {
            // Legacy soft-delete: the caller's OWN row is a tombstone — echo
            // nothing (a deleted identity must not surface). Preserved verbatim
            // from the pre-Slice-2 behaviour (Codex 019ea573, #770 Phase 2).
            responseUserId = null;
        } else if (discardedClaimMismatch) {
            // Hardened path: a FOREIGN numeric claim was discarded, but the
            // token's own identity (subject) is valid — echo the verified
            // subject, never the discarded foreign claim id.
            responseUserId = subject;
        } else {
            responseUserId = firstNonBlank(stringClaim(jwt, "userId"), stringClaim(jwt, "uid"), subject);
        }

        return new ResolvedAuthenticatedUser(numericUserId, responseUserId, email);
    }

    private Long lookupUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        if (!hasQueryableLocalUserTable()) {
            log.debug("Authenticated user lookup local tablo mevcut değil; SQL lookup atlanacak. table={}", userTable);
            return null;
        }
        String softDeleteFilter = softDeleteColumn == null ? "" : " and " + softDeleteColumn + " is null";
        String sql = "select id from " + userTable + " where lower(email) = ?" + softDeleteFilter + " limit 1";
        List<Map<String, Object>> rows;
        try {
            rows = jdbcTemplate.queryForList(sql, email.toLowerCase(Locale.ROOT));
        } catch (DataAccessException ex) {
            log.warn("Authenticated user lookup SQL başarısız oldu. cause={}", ex.getMessage());
            return null;
        }
        if (rows.isEmpty()) {
            return null;
        }
        Object idValue = rows.get(0).get("id");
        return idValue instanceof Number number ? number.longValue() : null;
    }

    /**
     * Resolve a numeric user id by the token's Keycloak subject — the
     * strongest verified identity (Slice 2, Codex 019ef349). Only used on the
     * hardened path ({@code kcSubjectColumn} configured). Tombstone-filtered
     * when a soft-delete column is set. Returns {@code null} when no subject,
     * no kc-subject column, the table is not queryable, or no active row.
     */
    private Long lookupUserIdByKcSubject(String subject) {
        if (subject == null || subject.isBlank() || kcSubjectColumn == null) {
            return null;
        }
        if (!hasQueryableLocalUserTable()) {
            return null;
        }
        String softDeleteFilter = softDeleteColumn == null ? "" : " and " + softDeleteColumn + " is null";
        String sql = "select id from " + userTable + " where " + kcSubjectColumn + " = ?" + softDeleteFilter + " limit 1";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, subject);
            if (rows.isEmpty()) {
                return null;
            }
            Object idValue = rows.get(0).get("id");
            return idValue instanceof Number number ? number.longValue() : null;
        } catch (DataAccessException ex) {
            log.warn("Authenticated user kc-subject lookup failed. cause={}", ex.getMessage());
            return null;
        }
    }

    /**
     * Cross-check (Slice 2, Codex 019ef349): is the claimed numeric id an
     * ACTIVE row that belongs to THIS token? True iff a non-tombstoned row at
     * {@code claimId} exists AND its {@code kc_subject} equals the token
     * {@code sub} OR its canonical email equals the token's canonical email.
     * Fail-closed: any query error or a missing/foreign row returns
     * {@code false}, so the claim is discarded (never trusted on doubt).
     */
    private boolean claimIdMatchesTokenIdentity(long claimId, String subject, String tokenEmail) {
        if (kcSubjectColumn == null || !hasQueryableLocalUserTable()) {
            return false;
        }
        String softDeleteFilter = softDeleteColumn == null ? "" : " and " + softDeleteColumn + " is null";
        String sql = "select " + kcSubjectColumn + " as kc_sub, email from " + userTable
                + " where id = ?" + softDeleteFilter + " limit 1";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, claimId);
            if (rows.isEmpty()) {
                return false;
            }
            Map<String, Object> row = rows.get(0);
            String rowSubject = row.get("kc_sub") == null ? null : String.valueOf(row.get("kc_sub"));
            String rowEmail = row.get("email") == null ? null : String.valueOf(row.get("email"));
            boolean subjectMatch = subject != null && subject.equals(blankToNull(rowSubject));
            boolean emailMatch = tokenEmail != null && rowEmail != null
                    && tokenEmail.toLowerCase(Locale.ROOT).equals(rowEmail.toLowerCase(Locale.ROOT));
            return subjectMatch || emailMatch;
        } catch (DataAccessException ex) {
            log.warn("Authenticated user claim cross-check failed (fail-closed → discard claim). id={} cause={}",
                    claimId, ex.getMessage());
            return false;
        }
    }

    /**
     * Confirms the given numeric id is an <em>active</em> (non-tombstoned)
     * row, used to validate a numeric claim when a soft-delete column is
     * configured (Codex 019ea573, #770 Phase 2). Fail-open: when the column
     * is unset or the table cannot be queried it returns {@code true} (no
     * suppression) — the user-service {@code CurrentUserResolver} choke point
     * remains the authoritative gate, this is defense-in-depth for the authz
     * context.
     */
    private boolean isActiveById(long userId) {
        if (softDeleteColumn == null || !hasQueryableLocalUserTable()) {
            return true;
        }
        String sql = "select id from " + userTable + " where id = ? and " + softDeleteColumn + " is null limit 1";
        try {
            return !jdbcTemplate.queryForList(sql, userId).isEmpty();
        } catch (DataAccessException ex) {
            log.warn("Authenticated user active-by-id check failed. id={} cause={}", userId, ex.getMessage());
            return true;
        }
    }

    private boolean hasQueryableLocalUserTable() {
        // Capability probe cache (Codex 019ef349): the relation's existence is
        // process-stable, so memoize a positive result to keep the hot
        // /authz/me + id-lookup path off a per-call to_regclass round-trip. Only
        // the TRUE outcome is cached — a transient false/error re-probes next
        // call so a one-off DB hiccup can't permanently disable lookups.
        if (Boolean.TRUE.equals(tableQueryableCache)) {
            return true;
        }
        try {
            String relationName = jdbcTemplate.queryForObject(
                    "select to_regclass(?)::text",
                    String.class,
                    userTable
            );
            boolean present = StringUtils.hasText(relationName);
            if (present) {
                tableQueryableCache = Boolean.TRUE;
            }
            return present;
        } catch (DataAccessException ex) {
            log.warn("Authenticated user lookup tablo kontrolü başarısız oldu. table={} cause={}",
                    userTable, ex.getMessage());
            return false;
        }
    }

    private static Long extractLongClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text) {
            return tryParseLong(text);
        }
        return null;
    }

    private static String stringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        return value == null ? null : blankToNull(String.valueOf(value));
    }

    private static Long tryParseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            String normalized = blankToNull(value);
            if (normalized != null) {
                return normalized;
            }
        }
        return null;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        if (values == null) {
            return null;
        }
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String normalizeTableName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (!QUALIFIED_TABLE_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid authz user table reference: " + raw);
        }
        return value;
    }

    private static String normalizeSoftDeleteColumn(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!COLUMN_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid soft-delete column reference: " + raw);
        }
        return value;
    }

    private static String normalizeColumnName(String raw, String label) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return null;
        }
        if (!COLUMN_NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid " + label + " column reference: " + raw);
        }
        return value;
    }

    public record ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email) {
    }
}
