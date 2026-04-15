package com.example.auth.security;

import com.example.auth.config.JwtProperties;
import java.time.Instant;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

/**
 * Legacy internal token provider (dev/local only). Prod/test’te auth-service yalnızca
 * Keycloak access tokenlarını oauth2ResourceServer(jwt) ile doğrular.
 */
@Component
@Deprecated
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    public JwtTokenProvider(JwtProperties jwtProperties,
                            JwtEncoder jwtEncoder,
                            JwtDecoder jwtDecoder) {
        this.jwtProperties = jwtProperties;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
    }

    public String generateToken(AuthenticatedUserDetails userDetails,
                                String role,
                                Set<String> permissions,
                                Long companyId,
                                int sessionTimeoutMinutes) {
        Instant now = Instant.now();
        Instant expiresAt = now.plusSeconds(Math.max(60L, sessionTimeoutMinutes * 60L));

        // PR6b (TB-11 Dalga 3): `permissions` claim artik yazilmiyor. D-002
        // identity-only JWT kurali. Downstream servisler /v1/authz/check ile
        // Zanzibar tuple kontrol yapar; JWT claim'den yetki cikartma kod tabani
        // disindadir (doctor A17 check sureklidir).
        // Parametre imzasi backward compat icin korundu (AuthService PR6a'da
        // Set.of() geciyor); ileride signature simplification ayri story.
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .subject(userDetails.getUsername())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim("uid", userDetails.getId())
                .claim("role", role);

        if (companyId != null) {
            claims.claim("companyId", companyId);
        }

        JwsHeader jwsHeader = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(jwsHeader, claims.build())).getTokenValue();
    }

    public boolean validateToken(String token) {
        try {
            jwtDecoder.decode(token);
            return true;
        } catch (JwtException ex) {
            return false;
        }
    }

    public String getUsernameFromToken(String token) {
        Jwt jwt = jwtDecoder.decode(token);
        return jwt.getSubject();
    }
}
