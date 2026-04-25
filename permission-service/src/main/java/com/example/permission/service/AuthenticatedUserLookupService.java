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

    public record ResolvedAuthenticatedUser(Long numericUserId, String responseUserId, String email) {
    }

    private record UserLookupResponse(Long id) {
    }
}
