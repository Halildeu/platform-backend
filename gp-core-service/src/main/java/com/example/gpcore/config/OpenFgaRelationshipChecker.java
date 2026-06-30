package com.example.gpcore.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.gpcore.domain.NodeRef;
import com.example.gpcore.domain.Principal;
import com.example.gpcore.port.RelationshipChecker;

import java.util.ArrayList;
import java.util.List;

/**
 * Production {@link RelationshipChecker} over the reused common-auth OpenFGA
 * client, bound to gp-core's SEPARATE store (ADR-0033 isolation).
 *
 * <p>Two hardening rules vs. the raw common-auth service (Codex 019f1913 #2):
 * <ol>
 *   <li><b>Fail-closed when disabled.</b> The shared service returns {@code true}
 *       for every check when OpenFGA is disabled (dev allow-all) — dangerous for
 *       an enforcement kernel. Here a disabled store DENIES, unless an explicit,
 *       profile-guarded {@code devBypass} flag is set.</li>
 *   <li><b>No version-unaware cache.</b> Only {@code checkNoCache} /
 *       {@code batchCheckNoCache} are used, so the version-aware decision cache in
 *       {@code AuthorizationDecisionService} is never shadowed by the service's
 *       10s positive cache (no stale-positive leak).</li>
 * </ol>
 */
public class OpenFgaRelationshipChecker implements RelationshipChecker {

    private final OpenFgaAuthzService service;
    private final boolean devBypass;

    public OpenFgaRelationshipChecker(OpenFgaAuthzService service, boolean devBypass) {
        this.service = service;
        this.devBypass = devBypass;
    }

    @Override
    public boolean canRelate(Principal principal, String relation, NodeRef ref) {
        if (!service.isEnabled()) {
            // Fail-closed: a disabled store must not grant. Explicit dev bypass only.
            return devBypass;
        }
        return service.checkNoCache(principal.userId(), relation, ref.type(), ref.id());
    }

    @Override
    public List<Boolean> canRelateBatch(Principal principal, List<RelationRequest> requests) {
        if (!service.isEnabled()) {
            List<Boolean> out = new ArrayList<>(requests.size());
            for (int i = 0; i < requests.size(); i++) {
                out.add(devBypass);
            }
            return out;
        }
        List<OpenFgaAuthzService.BatchCheckRequest> batch = new ArrayList<>(requests.size());
        for (RelationRequest r : requests) {
            batch.add(new OpenFgaAuthzService.BatchCheckRequest(r.relation(), r.ref().type(), r.ref().id()));
        }
        List<OpenFgaAuthzService.CheckResult> results = service.batchCheckNoCache(principal.userId(), batch);
        // Positionally align; any missing/short result is a fail-closed deny.
        List<Boolean> out = new ArrayList<>(requests.size());
        for (int i = 0; i < requests.size(); i++) {
            out.add(i < results.size() && results.get(i).allowed());
        }
        return out;
    }
}
