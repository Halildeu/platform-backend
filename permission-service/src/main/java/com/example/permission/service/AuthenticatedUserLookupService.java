package com.example.permission.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

@Service
public class AuthenticatedUserLookupService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticatedUserLookupService.class);
    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final JdbcTemplate jdbcTemplate;
    private final String userTable;
    private final RestClient userLookupClient;
    private final Function<String, Long> userLookupFallback;

    @Autowired
    public AuthenticatedUserLookupService(
            JdbcTemplate jdbcTemplate,
            @Value("${permission.authz.user-table:users}") String userTable,
            @Value("${permission.authz.user-lookup-base-url:http://user-service:8089}") String userLookupBaseUrl,
            RestClient.Builder restClientBuilder
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTable = normalizeTableName(userTable);
        this.userLookupClient = buildLookupClient(restClientBuilder, userLookupBaseUrl);
        this.userLookupFallback = this::lookupUserIdByEmailViaUserService;
    }

    AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable) {
        this(jdbcTemplate, userTable, email -> null);
    }

    AuthenticatedUserLookupService(JdbcTemplate jdbcTemplate, String userTable, Function<String, Long> userLookupFallback) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTable = normalizeTableName(userTable);
        this.userLookupClient = null;
        this.userLookupFallback = userLookupFallback == null ? email -> null : userLookupFallback;
    }

    public ResolvedAuthenticatedUser resolve(Jwt jwt) {
        if (jwt == null) {
            return new ResolvedAuthenticatedUser(null, null, null, false);
        }

        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));

        // Slice 2b cheap guard (#727, Codex 019ef3ca): a numeric userId/uid
        // CLAIM is NOT trusted as authority when the token's subject is a
        // non-numeric Keycloak UUID and an email is present — such a claim may
        // be stale/foreign (the cross-user risk this slice closes). Resolve by
        // the token's verified email instead, and NEVER fall back to the raw
        // claim (fail-closed). permission-service can't run the 2a-style local
        // kc_subject cross-check (its `user_service.users` is cross-DB →
        // to_regclass null → RestClient /by-email which returns only {id}), so
        // email-authority is the available verified-identity path here.
        // Browser/M365 tokens carry NO numeric claim (sub+email only) → this
        // guard never engages for them (no added hot-path call); it only bites
        // legacy/service claim-bearing tokens.
        Long claim = firstNonNull(extractLongClaim(jwt, "userId"), extractLongClaim(jwt, "uid"));
        boolean distrustClaim = claim != null && email != null
                && subject != null && tryParseLong(subject) == null;

        Long numericUserId;
        if (distrustClaim) {
            numericUserId = lookupUserIdByEmail(email);
        } else {
            numericUserId = firstNonNull(claim, tryParseLong(subject));
            if (numericUserId == null && email != null) {
                numericUserId = lookupUserIdByEmail(email);
            }
        }

        String responseUserId;
        if (numericUserId != null) {
            responseUserId = Long.toString(numericUserId);
        } else if (distrustClaim) {
            // Distrusted claim + email unresolved → fail-closed; echo the
            // verified subject (KC UUID), NEVER the discarded numeric claim.
            responseUserId = subject;
        } else {
            responseUserId = firstNonBlank(stringClaim(jwt, "userId"), stringClaim(jwt, "uid"), subject);
        }

        return new ResolvedAuthenticatedUser(numericUserId, responseUserId, email, distrustClaim);
    }

    private Long lookupUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }

        String normalizedEmail = email.toLowerCase(Locale.ROOT);
        if (hasQueryableLocalUserTable()) {
            try {
                String sql = "select id from " + userTable + " where lower(email) = ? limit 1";
                List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, normalizedEmail);
                if (!rows.isEmpty()) {
                    Object idValue = rows.get(0).get("id");
                    return idValue instanceof Number number ? number.longValue() : null;
                }
            } catch (DataAccessException ex) {
                log.warn("Authz user lookup SQL ile çözülemedi; user-service fallback denenecek. cause={}", ex.getMessage());
            }
        } else {
            log.debug("Authz user lookup local tablo mevcut değil; user-service fallback kullanılacak. table={}", userTable);
        }

        return userLookupFallback.apply(normalizedEmail);
    }

    private Long lookupUserIdByEmailViaUserService(String email) {
        if (userLookupClient == null || !StringUtils.hasText(email)) {
            return null;
        }

        try {
            UserLookupResponse response = userLookupClient.get()
                    .uri("/api/users/by-email/{email}", email)
                    .retrieve()
                    .body(UserLookupResponse.class);
            return response == null ? null : response.id();
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().value() == 404) {
                return null;
            }
            log.warn("Authz user lookup user-service fallback HTTP {} ile başarısız oldu.", ex.getStatusCode().value());
            return null;
        } catch (RestClientException ex) {
            log.warn("Authz user lookup user-service fallback başarısız oldu. cause={}", ex.getMessage());
            return null;
        }
    }

    private boolean hasQueryableLocalUserTable() {
        try {
            String relationName = jdbcTemplate.queryForObject(
                    "select to_regclass(?)::text",
                    String.class,
                    userTable
            );
            return StringUtils.hasText(relationName);
        } catch (DataAccessException ex) {
            log.warn("Authz user lookup tablo kontrolü başarısız oldu; user-service fallback kullanılacak. table={} cause={}",
                    userTable, ex.getMessage());
            return false;
        }
    }

    private static RestClient buildLookupClient(RestClient.Builder restClientBuilder, String baseUrl) {
        if (restClientBuilder == null || !StringUtils.hasText(baseUrl)) {
            return null;
        }
        return restClientBuilder
                .baseUrl(baseUrl.trim())
                .build();
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
            throw new IllegalArgumentException("Invalid permission authz user table reference: " + raw);
        }
        return value;
    }

    /**
     * @param claimDistrusted Slice 2b (#727, Codex 019ef3ca REVISE): true when a
     *        numeric userId/uid claim was DISTRUSTED by the cheap guard (UUID
     *        subject + email present). When this is true AND numericUserId is
     *        null, callers (notably /authz/me) MUST fail closed — they must NOT
     *        rebuild authz from JWT permissions/roles claims, which would re-open
     *        the very claim-as-authority hole this slice closes.
     */
    public record ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email,
                                            boolean claimDistrusted) {
        /** Backward-compatible 3-arg form (claimDistrusted=false) for callers/tests
         *  that don't set the guard flag. */
        public ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email) {
            this(numericUserId, responseUserId, email, false);
        }
    }

    private record UserLookupResponse(Long id) {
    }
}
