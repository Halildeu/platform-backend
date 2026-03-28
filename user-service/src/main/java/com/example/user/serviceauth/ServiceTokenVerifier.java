package com.example.user.serviceauth;

import com.example.user.security.ServiceAuthenticationToken;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

@Component
public class ServiceTokenVerifier {

    private final ServiceTokenVerificationProperties verificationProperties;
    private final JwtDecoder jwtDecoder;

    public ServiceTokenVerifier(ServiceTokenVerificationProperties verificationProperties) {
        this.verificationProperties = verificationProperties;
        this.jwtDecoder = buildDecoder(verificationProperties);
    }

    public ServiceAuthenticationToken verify(String token) throws JwtException {
        Jwt jwt = jwtDecoder.decode(token);

        // Önce bu token bir servis token’ı mı diye ayırt et (svc claim'i zorunlu)
        String serviceId = jwt.getClaimAsString("svc");
        if (!StringUtils.hasText(serviceId)) {
            // Kullanıcı JWT'si olabilir; ServiceAuthenticationToken oluşturmayıp üst zincire bırak
            throw new JwtException("Not a service token (missing svc)");
        }

        // Servis token’ları için audience ve environment doğrulaması
        var audiences = jwt.getAudience();
        Set<String> expectedAudiences = splitCsv(verificationProperties.getExpectedAudience());
        if (!expectedAudiences.isEmpty()
                && (audiences == null || audiences.stream().noneMatch(expectedAudiences::contains))) {
            throw new JwtException("Unexpected audience");
        }

        String environment = jwt.getClaimAsString("env");
        if (StringUtils.hasText(verificationProperties.getExpectedEnvironment())
                && !verificationProperties.getExpectedEnvironment().equals(environment)) {
            throw new JwtException("Unexpected environment");
        }

        Collection<? extends GrantedAuthority> authorities = extractAuthorities(jwt.getClaim("perm"));
        return new ServiceAuthenticationToken(serviceId, environment, authorities);
    }

    private JwtDecoder buildDecoder(ServiceTokenVerificationProperties props) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(props.getJwkSetUri()).build();

        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(props.getIssuer());
        decoder.setJwtValidator(validator);
        return decoder;
    }

    private Collection<? extends GrantedAuthority> extractAuthorities(Object permClaim) {
        if (permClaim == null) {
            return List.of();
        }
        List<String> permissions = new ArrayList<>();
        if (permClaim instanceof Collection<?> collection) {
            for (Object entry : collection) {
                if (entry instanceof String str && StringUtils.hasText(str)) {
                    permissions.add(str);
                }
            }
        } else if (permClaim instanceof String str && StringUtils.hasText(str)) {
            permissions.add(str);
        }
        if (CollectionUtils.isEmpty(permissions)) {
            return List.of();
        }
        return permissions.stream()
                .filter(StringUtils::hasText)
                .map(value -> new SimpleGrantedAuthority("PERM_" + value))
                .toList();
    }

    private Set<String> splitCsv(String raw) {
        if (!StringUtils.hasText(raw)) {
            return Set.of();
        }
        return java.util.Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toSet());
    }
}
