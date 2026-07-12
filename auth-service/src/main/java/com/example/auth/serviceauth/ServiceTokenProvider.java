package com.example.auth.serviceauth;

import com.example.auth.config.JwtProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.stereotype.Component;

@Component
public class ServiceTokenProvider {

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenProvider.class);

    private final JwtProperties jwtProperties;
    private final ServiceTokenProperties serviceTokenProperties;
    private final ServiceJwtKeyProperties serviceJwtKeyProperties;
    private final JwtEncoder serviceJwtEncoder;
    private final String serviceId;

    private final Map<TokenKey, TokenCache> cache = new ConcurrentHashMap<>();

    public ServiceTokenProvider(JwtProperties jwtProperties,
                                ServiceTokenProperties serviceTokenProperties,
                                ServiceJwtKeyProperties serviceJwtKeyProperties,
                                JwtEncoder serviceJwtEncoder,
                                @Value("${spring.application.name:auth-service}") String serviceId) {
        this.jwtProperties = jwtProperties;
        this.serviceTokenProperties = serviceTokenProperties;
        this.serviceJwtKeyProperties = serviceJwtKeyProperties;
        this.serviceJwtEncoder = serviceJwtEncoder;
        this.serviceId = serviceId;
    }

    public String getToken() {
        return getToken(serviceTokenProperties.getAudience(), serviceTokenProperties.getPermissions());
    }

    public String getToken(String audience, List<String> permissions) {
        return getToken(serviceId, false, audience, permissions);
    }

    /**
     * Mint a client-credentials access token bound to the authenticated OAuth client.
     *
     * <p>RFC 9068 uses {@code client_id} for the client identifier and client-credentials
     * tokens identify the client as the subject when there is no resource owner. The
     * existing {@code svc} claim remains for downstream service-token compatibility.
     */
    public String getTokenForClient(String clientId, String audience, List<String> permissions) {
        if (clientId == null || clientId.isBlank()) {
            throw new IllegalArgumentException("clientId must not be blank");
        }
        return getToken(clientId, true, audience, permissions);
    }

    private String getToken(String principalId,
                            boolean clientCredentials,
                            String audience,
                            List<String> permissions) {
        List<String> normalizedPermissions = normalizePermissions(permissions);
        TokenKey key = new TokenKey(
                principalId, clientCredentials, audience, normalizedPermissions);
        Instant now = Instant.now();

        TokenCache cached = cache.get(key);
        if (cached == null || now.isAfter(cached.refreshAfter())) {
            synchronized (cache) {
                cached = cache.get(key);
                if (cached == null || now.isAfter(cached.refreshAfter())) {
                    cached = issueToken(now, key);
                    cache.put(key, cached);
                }
            }
        }
        return Objects.requireNonNull(cached).value();
    }

    private TokenCache issueToken(Instant now, TokenKey key) {
        long ttlSeconds = Math.max(30, serviceTokenProperties.getTtlSeconds());
        Instant expiresAt = now.plusSeconds(ttlSeconds);
        Instant refreshAfter = expiresAt.minusSeconds(Math.min(30, ttlSeconds / 2));

        log.debug("Servis token üretiliyor -> audience={}, permissions={}, expiresAt={}", key.audience(), key.permissions(), expiresAt);

        JwtClaimsSet.Builder claimsBuilder = JwtClaimsSet.builder()
                .subject(key.principalId())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("svc", key.principalId())
                .claim("env", serviceTokenProperties.getEnvironment())
                .audience(List.of(key.audience()));

        if (key.clientCredentials()) {
            claimsBuilder.claim("client_id", key.principalId());
        }

        if (!key.permissions().isEmpty()) {
            claimsBuilder.claim("perm", key.permissions());
        }

        JwtClaimsSet claims = claimsBuilder.build();
        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(serviceJwtKeyProperties.getKeyId())
                .build();

        String token = serviceJwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims)).getTokenValue();
        return new TokenCache(token, refreshAfter, expiresAt);
    }

    private List<String> normalizePermissions(List<String> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            return List.of();
        }
        TreeSet<String> normalized = new TreeSet<>();
        permissions.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(permission -> !permission.isBlank())
                .forEach(normalized::add);
        return List.copyOf(normalized);
    }

    private record TokenCache(String value, Instant refreshAfter, Instant expiresAt) {
    }

    private record TokenKey(String principalId,
                            boolean clientCredentials,
                            String audience,
                            List<String> permissions) {
    }
}
