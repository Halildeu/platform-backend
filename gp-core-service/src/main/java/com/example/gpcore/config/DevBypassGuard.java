package com.example.gpcore.config;

import java.util.Collection;
import java.util.Set;

/**
 * Fail-fast guard for the OpenFGA dev-bypass escape hatch (Codex 019f1913
 * post-impl #3). {@code gp.authz.dev-bypass=true} turns the relationship checker
 * into allow-all when the store is disabled — acceptable ONLY in local dev. This
 * makes that constraint enforced (not merely documented): the application refuses
 * to start if dev-bypass is enabled outside a dev profile.
 */
public final class DevBypassGuard {

    static final Set<String> DEV_PROFILES = Set.of("local", "dev", "test");

    private DevBypassGuard() {
    }

    /**
     * @throws IllegalStateException if {@code devBypass} is true and no dev profile
     *         ({@code local}/{@code dev}/{@code test}) is active.
     */
    public static void validate(boolean devBypass, Collection<String> activeProfiles) {
        if (!devBypass) {
            return;
        }
        boolean anyDev = activeProfiles != null
                && activeProfiles.stream().anyMatch(DEV_PROFILES::contains);
        if (!anyDev) {
            throw new IllegalStateException(
                    "gp.authz.dev-bypass=true is permitted ONLY under a dev profile "
                            + DEV_PROFILES + "; active profiles=" + activeProfiles
                            + ". Refusing to start with OpenFGA authorization bypassed in a non-dev context.");
        }
    }
}
