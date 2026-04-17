package com.example.user.security;

import com.example.user.serviceauth.ServiceTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * P1.8 (STORY-0320): {@code @Profile} kaldırıldı — filter tüm profile'larda
 * aktif (local/dev/prod/docker/test). Önceden yalnız local/dev'de mount
 * ediliyordu, bu yüzden non-local profile'larda auth-service tarafından mint
 * edilen servis token'ları reddediliyordu. Filter artık her zaman bean
 * halinde hazır; hem {@link SecurityConfigLocal} hem non-local
 * {@link SecurityConfig} {@code /api/users/internal/**} chain'ine wire edebilir.
 * {@link com.example.user.serviceauth.ServiceTokenVerifier} zaten profile
 * bağımsız bir {@code @Component}.
 */
@Component
public class ServiceTokenAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(ServiceTokenAuthenticationFilter.class);

    private final ServiceTokenVerifier tokenVerifier;

    public ServiceTokenAuthenticationFilter(ServiceTokenVerifier tokenVerifier) {
        this.tokenVerifier = tokenVerifier;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        Authentication existing = SecurityContextHolder.getContext().getAuthentication();
        if (existing instanceof ServiceAuthenticationToken) {
            filterChain.doFilter(request, response);
            return;
        }

        String bearerToken = resolveBearerToken(request);
        if (StringUtils.hasText(bearerToken)) {
            try {
                ServiceAuthenticationToken authenticationToken = tokenVerifier.verify(bearerToken);
                SecurityContextHolder.getContext().setAuthentication(authenticationToken);
            } catch (Exception ex) {
                logger.warn("Service token doğrulaması başarısız: {}", ex.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }

    private String resolveBearerToken(HttpServletRequest request) {
        String authorization = request.getHeader("Authorization");
        if (StringUtils.hasText(authorization) && authorization.startsWith("Bearer ")) {
            return authorization.substring(7);
        }
        return null;
    }
}
