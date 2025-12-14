package com.example.user.security;

import com.example.user.serviceauth.ServiceTokenVerifier;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.lang.NonNull;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile({"local", "dev"})
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
