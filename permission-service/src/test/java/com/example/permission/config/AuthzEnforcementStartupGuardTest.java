package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * #933: proves the fail-closed half of the fix.
 *
 * <p>Making {@code OpenFgaAuthzService} unconditional stopped the context from crashing when
 * {@code erp.openfga.enabled=false}, but that alone would let a deployed service run with every
 * {@code @RequireModule} guard permitting requests. These tests pin the boundary: permissive in
 * a local/dev context, refuses to start in a deployed profile.
 */
class AuthzEnforcementStartupGuardTest {

    private AuthzEnforcementStartupGuard guard(boolean enforcementEnabled,
                                               String requiredProfilesCsv,
                                               boolean allowOverride,
                                               String... activeProfiles) {
        OpenFgaAuthzService authzService = mock(OpenFgaAuthzService.class);
        when(authzService.isEnabled()).thenReturn(enforcementEnabled);
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles(activeProfiles);
        return new AuthzEnforcementStartupGuard(authzService, env, requiredProfilesCsv, allowOverride);
    }

    @Test
    @DisplayName("enforcement ON in a deployed profile — starts")
    void startsWhenEnforcementOnInDeployedProfile() {
        assertDoesNotThrow(() -> guard(true, "k8s", false, "k8s").afterPropertiesSet());
    }

    @Test
    @DisplayName("enforcement OFF with no deployed profile — starts (documented dev/permitAll mode)")
    void startsWhenEnforcementOffOutsideDeployedProfile() {
        assertDoesNotThrow(() -> guard(false, "k8s", false).afterPropertiesSet());
        assertDoesNotThrow(() -> guard(false, "k8s", false, "local").afterPropertiesSet());
    }

    @Test
    @DisplayName("enforcement OFF in a deployed profile — refuses to start, and the message names the flag")
    void failsClosedWhenEnforcementOffInDeployedProfile() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard(false, "k8s", false, "k8s").afterPropertiesSet());
        // The old failure said "No qualifying bean of type OpenFgaAuthzService", which never
        // mentioned the cause. Diagnosability is the point, so assert on it.
        assertTrue(ex.getMessage().contains("erp.openfga.enabled"),
                "message must name the property; was: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("k8s"),
                "message must name the offending profile; was: " + ex.getMessage());
    }

    @Test
    @DisplayName("required-profiles is configurable — a new deployed tier needs no code change")
    void requiredProfilesIsConfigurable() {
        assertThrows(IllegalStateException.class,
                () -> guard(false, "k8s,staging", false, "staging").afterPropertiesSet());
        // A profile outside the configured set stays permissive.
        assertDoesNotThrow(() -> guard(false, "k8s,staging", false, "sandbox").afterPropertiesSet());
    }

    @Test
    @DisplayName("override is explicit and greppable, never a silent default")
    void explicitOverrideAllowsDisabledInDeployedProfile() {
        assertDoesNotThrow(() -> guard(false, "k8s", true, "k8s").afterPropertiesSet());
    }
}
