package com.example.permission.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collection;
import org.springframework.core.convert.converter.Converter;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Ek olarak kullanıcı bazlı JWT'leri doğrulamak için kullanılan filtre.
 * Servis token'ları Resource Server tarafından doğrulanırken, bu filtre HS384 ile imzalanmış
 * kullanıcı token'larını doğrular ve SecurityContext'e taşır.
 */
public class UserJwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtDecoder userJwtDecoder;
    private final Converter<Jwt, Collection<GrantedAuthority>> authorityConverter;

    public UserJwtAuthenticationFilter(JwtDecoder userJwtDecoder,
                                       Converter<Jwt, Collection<GrantedAuthority>> authorityConverter) {
        this.userJwtDecoder = userJwtDecoder;
        this.authorityConverter = authorityConverter;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (userJwtDecoder == null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        String bearerToken = resolveBearerToken(request);
        if (!StringUtils.hasText(bearerToken)) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Jwt jwt = userJwtDecoder.decode(bearerToken);
            Collection<? extends GrantedAuthority> authorities = authorityConverter.convert(jwt);
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    jwt.getSubject(), jwt, authorities);
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (JwtException ex) {
            // Kullanıcı token'ı olarak doğrulanamazsa Resource Server doğrulamasına bırakılır.
        }

        filterChain.doFilter(request, response);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (StringUtils.hasText(header) && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        return null;
    }
}
