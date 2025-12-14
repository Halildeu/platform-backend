package com.example.permission.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * Servis token'ları ve kullanıcı token'larını ayrıştırmak için iki farklı converter'ı zincirler.
 */
public class CompositeJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final JwtAuthenticationConverter serviceConverter;
    private final JwtAuthenticationConverter userConverter;

    public CompositeJwtAuthenticationConverter(JwtAuthenticationConverter serviceConverter,
                                               JwtAuthenticationConverter userConverter) {
        this.serviceConverter = serviceConverter;
        this.userConverter = userConverter;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        if (jwt.hasClaim("svc")) {
            return serviceConverter.convert(jwt);
        }
        return userConverter.convert(jwt);
    }
}
