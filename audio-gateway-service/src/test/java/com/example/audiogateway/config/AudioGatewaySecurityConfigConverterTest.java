package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * #716 Step-7 enforce-flip readiness — capability-role extraction unit coverage.
 *
 * <p>The converter pulls {@code resource_access.<resourceClientId>.roles} into Spring
 * authorities (see {@link SecurityConfig#jwtAuthenticationConverter()}). This is the
 * input half of the enforce gate: once the operator flips
 * {@code requireAudioRecordRole=true}, a token is admitted ONLY if this extraction
 * yields {@code audio_record}. Machine-enforcing the extraction here means the flip is
 * proven fail-closed BEFORE it happens. Pairs with {@link AudienceValidatorTest} (aud)
 * + {@code AudioGatewayEnforceAuthorizationTest} (the filter-chain rule).
 */
class AudioGatewaySecurityConfigConverterTest {

    private static final String CLIENT = "audio-gateway-service";
    private static final String ROLE = "audio_record";

    private final SecurityConfig config = new SecurityConfig(props());

    private static AudioGatewaySecurityProperties props() {
        final AudioGatewaySecurityProperties p = new AudioGatewaySecurityProperties();
        p.setResourceClientId(CLIENT);
        return p;
    }

    private static Jwt jwt(final Map<String, Object> claims) {
        final Jwt.Builder b = Jwt.withTokenValue("token").header("alg", "none").subject("user-1");
        claims.forEach(b::claim);
        return b.build();
    }

    private List<String> authorities(final Jwt jwt) {
        return config.jwtAuthenticationConverter().convert(jwt).block().getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .toList();
    }

    @Test
    void extractsAudioRecordRoleWhenPresentForThisClient() {
        final Jwt jwt = jwt(Map.of("resource_access", Map.of(CLIENT, Map.of("roles", List.of(ROLE)))));
        assertThat(authorities(jwt)).contains(ROLE);
    }

    @Test
    void noRoleWhenResourceAccessAbsent() {
        final Jwt jwt = jwt(Map.of("scope", "profile"));
        assertThat(authorities(jwt)).doesNotContain(ROLE);
    }

    @Test
    void noRoleWhenResourceAccessTargetsDifferentClient() {
        // Defense-in-depth: a role granted to ANOTHER client in the shared realm must
        // NOT leak into this resource's authorities (the whole point of #716).
        final Jwt jwt = jwt(Map.of("resource_access", Map.of("some-other-client", Map.of("roles", List.of(ROLE)))));
        assertThat(authorities(jwt)).doesNotContain(ROLE);
    }

    @Test
    void extractsEveryRoleForThisClient() {
        final Jwt jwt =
                jwt(Map.of("resource_access", Map.of(CLIENT, Map.of("roles", List.of(ROLE, "extra_role")))));
        assertThat(authorities(jwt)).contains(ROLE, "extra_role");
    }
}
