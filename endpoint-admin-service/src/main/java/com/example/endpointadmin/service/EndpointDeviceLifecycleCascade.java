package com.example.endpointadmin.service;

import com.example.endpointadmin.model.EndpointDevice;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Decommission cascade — invalidates pending agent work so a later reactivate
 * cannot resurrect stale intent (Codex 019ea789 must-fix). Runs inside the
 * decommission transaction ({@code Propagation.MANDATORY}).
 *
 * <p>NOTE: this class is the cascade SEAM. The actual cascade body
 * (cancel non-terminal endpoint_commands + clear their command secrets; revoke
 * pending maintenance tokens; finalize open uninstall requests to TERMINAL with
 * {@code cancelled_by_decommission}) lands in the next commit of this PR. The
 * lifecycle audit + hash-chain event record the returned counts, and the PR's
 * cascade regression tests assert non-zero behaviour — so this no-op cannot
 * ship silently.
 */
@Component
public class EndpointDeviceLifecycleCascade {

    /** Counts of cascaded side effects, surfaced in the lifecycle audit row. */
    public record CascadeCounts(int cancelledCommands, int revokedTokens, int finalizedUninstalls) {
        public static CascadeCounts none() {
            return new CascadeCounts(0, 0, 0);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public CascadeCounts onDecommission(UUID tenantId, EndpointDevice device,
                                        String actorSubject, String reason) {
        // commit-3 (Codex 019ea789 must-fix): implement the cascade —
        //   1. cancel non-terminal endpoint_commands (QUEUED/DELIVERED/ACKED/
        //      RUNNING -> CANCELLED + cancelled_at) and clear their command
        //      secrets (CHANGE_LOCAL_PASSWORD etc. via EndpointCommandSecretService).
        //   2. revoke/expire pending endpoint maintenance tokens.
        //   3. finalize open uninstall requests -> TERMINAL (cancelled_by_decommission).
        return CascadeCounts.none();
    }
}
