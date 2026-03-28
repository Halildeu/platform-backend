package com.example.permission.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class AuthenticatedUserLookupService {

    private static final Pattern QUALIFIED_TABLE_NAME =
            Pattern.compile("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)?");

    private final JdbcTemplate jdbcTemplate;
    private final String userTable;

    public AuthenticatedUserLookupService(
            JdbcTemplate jdbcTemplate,
            @Value("${permission.authz.user-table:users}") String userTable
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.userTable = normalizeTableName(userTable);
    }

    public ResolvedAuthenticatedUser resolve(Jwt jwt) {
        if (jwt == null) {
            return new ResolvedAuthenticatedUser(null, null, null);
        }

        String subject = blankToNull(jwt.getSubject());
        String email = firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));

        Long numericUserId = firstNonNull(
                extractLongClaim(jwt, "userId"),
                extractLongClaim(jwt, "uid"),
                tryParseLong(subject)
        );

        if (numericUserId == null && email != null) {
            numericUserId = lookupUserIdByEmail(email);
        }

        String responseUserId = numericUserId != null
                ? Long.toString(numericUserId)
                : firstNonBlank(stringClaim(jwt, "userId"), stringClaim(jwt, "uid"), subject);

        return new ResolvedAuthenticatedUser(numericUserId, responseUserId, email);
    }

    private Long lookupUserIdByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        String sql = "select id from " + userTable + " where lower(email) = ? limit 1";
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, email.toLowerCase(Locale.ROOT));
        if (rows.isEmpty()) {
            return null;
        }
        Object idValue = rows.get(0).get("id");
        return idValue instanceof Number number ? number.longValue() : null;
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

    public record ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email) {
    }
}
