package com.example.user.security;

import com.example.user.config.JwtProperties;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Component;

@Component
@Profile({"local", "dev"})
@ConditionalOnBean(JwtDecoder.class)
@Deprecated // Legacy HS/RS token provider; prod/test token doğrulaması oauth2ResourceServer(jwt) üzerinden yapılır.
public class JwtTokenProvider {

    private final JwtProperties jwtProperties;
    private final JwtEncoder jwtEncoder;
    private final JwtDecoder jwtDecoder;

    public JwtTokenProvider(JwtProperties jwtProperties,
                            @Qualifier("serviceJwtEncoder") JwtEncoder jwtEncoder,
                            JwtDecoder jwtDecoder) {
        this.jwtProperties = jwtProperties;
        this.jwtEncoder = jwtEncoder;
        this.jwtDecoder = jwtDecoder;
    }

    public String generateToken(UserDetails userDetails) {
        Instant now = Instant.now();
        Instant expiry = now.plusMillis(jwtProperties.getExpiration());

        String role = userDetails.getAuthorities().stream()
                .findFirst()
                .map(auth -> auth.getAuthority().replace("ROLE_", ""))
                .orElse("USER");

        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject(userDetails.getUsername())
                .issuer(jwtProperties.getIssuer())
                .issuedAt(now)
                .expiresAt(expiry)
                .claim("role", role)
                .build();

        JwsHeader headers = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return jwtEncoder.encode(JwtEncoderParameters.from(headers, claims)).getTokenValue();
    }

    public String getUsernameFromToken(String token) {
        return jwtDecoder.decode(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            jwtDecoder.decode(token);
            return true;
        } catch (JwtException ex) {
            return false;
        }
    }
}
