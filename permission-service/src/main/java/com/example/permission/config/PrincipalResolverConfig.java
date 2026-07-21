package com.example.permission.config;

import com.example.commonauth.identity.AuthenticatedPrincipalResolver;
import com.example.commonauth.identity.UserIdentityDirectory;
import com.example.permission.security.AuthenticatedUserLookupIdentityDirectory;
import com.example.permission.service.AuthenticatedUserLookupService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the canonical principal resolver (board #2532 wire step).
 *
 * <p>The port lives in {@code common-auth} and knows nothing about transports. Here we bind it to
 * the wrapper adapter (see {@link AuthenticatedUserLookupIdentityDirectory} for why this wraps
 * rather than calls the canonical endpoint directly) and expose a single
 * {@link AuthenticatedPrincipalResolver} bean for the controller wiring.
 */
@Configuration
public class PrincipalResolverConfig {

    @Bean
    public UserIdentityDirectory permissionServiceUserIdentityDirectory(
            AuthenticatedUserLookupService lookupService) {
        return new AuthenticatedUserLookupIdentityDirectory(lookupService);
    }

    @Bean
    public AuthenticatedPrincipalResolver authenticatedPrincipalResolver(
            UserIdentityDirectory directory) {
        return new AuthenticatedPrincipalResolver(directory);
    }
}
