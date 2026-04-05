package com.example.auth.serviceauth;

import com.example.auth.config.JwtProperties;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        List<String> normalizedPermissions = permissions == null ? List.of() : List.copyOf(permissions);
        TokenKey key = new TokenKey(audience, normalizedPermissions);
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
                .subject(serviceId)
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("svc", serviceId)
                .claim("env", serviceTokenProperties.getEnvironment())
                .audience(List.of(key.audience()));

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

    private record TokenCache(String value, Instant refreshAfter, Instant expiresAt) {
    }

    private record TokenKey(String audience, List<String> permissions) {
    }
}
