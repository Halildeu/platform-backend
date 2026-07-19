package com.example.endpointadmin.config;

import com.example.endpointadmin.remoteaccess.preflight.ViewOnlyAuthorityProperties;
import com.example.endpointadmin.remoteaccess.preflight.ViewOnlyGithubOidcProfile;
import com.example.endpointadmin.remoteaccess.preflight.ViewOnlyGithubOidcValidator;
import com.example.endpointadmin.remoteaccess.preflight.ViewOnlySecurityErrorWriter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/** Dedicated GitHub OIDC chains; never falls through to the product Keycloak decoder. */
@Configuration
@Profile("!local & !dev")
@EnableConfigurationProperties(ViewOnlyAuthorityProperties.class)
@ConditionalOnProperty(prefix = "endpoint-admin.view-only-authority", name = "enabled", havingValue = "true")
public class ViewOnlyAuthoritySecurityConfig {
    private static final String ROOT = "/api/v1/endpoint-admin/remote-access/preflight";

    @Bean
    public ViewOnlySecurityErrorWriter viewOnlySecurityErrorWriter(ObjectMapper mapper) {
        return new ViewOnlySecurityErrorWriter(mapper);
    }

    @Bean
    @Order(-30)
    public SecurityFilterChain viewOnlyPreflightOidcChain(HttpSecurity http,
                                                          ViewOnlyAuthorityProperties properties,
                                                          Clock clock,
                                                          ViewOnlySecurityErrorWriter errors) throws Exception {
        return chain(http, ROOT + "/attest", ViewOnlyGithubOidcProfile.PREFLIGHT, properties, clock, errors);
    }

    @Bean
    @Order(-29)
    public SecurityFilterChain viewOnlyLeaseOidcChain(HttpSecurity http,
                                                      ViewOnlyAuthorityProperties properties,
                                                      Clock clock,
                                                      ViewOnlySecurityErrorWriter errors) throws Exception {
        return chain(http, ROOT + "/checkpoint-leases/redeem",
                ViewOnlyGithubOidcProfile.AUTHORIZATION, properties, clock, errors);
    }

    @Bean
    @Order(-28)
    public SecurityFilterChain viewOnlyCheckpointOidcChain(HttpSecurity http,
                                                           ViewOnlyAuthorityProperties properties,
                                                           Clock clock,
                                                           ViewOnlySecurityErrorWriter errors) throws Exception {
        return chain(http, ROOT + "/checkpoints/**", ViewOnlyGithubOidcProfile.EXECUTOR, properties, clock, errors);
    }

    private SecurityFilterChain chain(HttpSecurity http,
                                      String matcher,
                                      ViewOnlyGithubOidcProfile profile,
                                      ViewOnlyAuthorityProperties properties,
                                      Clock clock,
                                      ViewOnlySecurityErrorWriter errors) throws Exception {
        properties.validateActivation();
        http.securityMatcher(matcher)
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors))
                .oauth2ResourceServer(oauth -> oauth
                        .authenticationEntryPoint(errors)
                        .accessDeniedHandler(errors)
                        .jwt(jwt -> jwt.decoder(decoder(profile, properties, clock))));
        return http.build();
    }

    private JwtDecoder decoder(ViewOnlyGithubOidcProfile profile,
                               ViewOnlyAuthorityProperties properties,
                               Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.getJwksUri()).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(properties.getIssuer()),
                new ViewOnlyGithubOidcValidator(
                        profile, clock, Duration.ofSeconds(properties.getMaximumClockSkewSeconds()))));
        return decoder;
    }
}
