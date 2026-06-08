package com.example.endpointadmin.service;

import com.example.endpointadmin.event.CommandApprovalDecidedEvent;
import com.example.endpointadmin.model.ApprovalDecision;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.model.DisplayPolicyOperation;
import com.example.endpointadmin.model.EndpointDisplayPolicy;
import com.example.endpointadmin.model.EndpointDisplayPolicyRevision;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRepository;
import com.example.endpointadmin.repository.EndpointDisplayPolicyRevisionRepository;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

/**
 * #508 slice-2b — promotes a SET_DISPLAY_POLICY revision to the current
 * desired-state row when (and only when) a second admin APPROVES the backing
 * maker-checker command (Codex 019ea911 RED-fix).
 *
 * <p><b>Why a listener, not a PUT-time write:</b> the dual-control approve
 * surface ({@code EndpointAdminCommandService.approveCommand}) is generic and
 * does not know about display-policy domain state. If the PUT path wrote the
 * current row eagerly, a REJECTED proposal would still leave the new
 * desired-state as "current truth", bypassing maker-checker at the state level.
 * Instead PUT/DELETE write only a revision + a PENDING command; this listener
 * reacts to the approval decision:
 * <ul>
 *   <li><b>APPROVE</b> → upsert the current row from the backing REVISION (never
 *       from the command payload), flipping ENFORCE ⇄ CLEAR in place.</li>
 *   <li><b>REJECT</b> → no-op; the proposal dies, current stays unchanged.</li>
 * </ul>
 *
 * <p><b>Transaction contract (Codex must-fix #3):</b> {@code @EventListener} is
 * synchronous (no async multicaster configured) and
 * {@code @Transactional(MANDATORY)} forces it to run inside the approve
 * transaction — so a thrown exception here rolls the approval back atomically.
 * It never uses {@code @TransactionalEventListener(AFTER_COMMIT)} (which cannot
 * roll back).
 *
 * <p><b>No device lock (Codex must-fix #2):</b> the listener takes NO
 * pessimistic lock. The one-open-proposal-per-device invariant (enforced at PUT
 * under the device write-lock) guarantees ≤1 PENDING SET_DISPLAY_POLICY command
 * per device ⇒ ≤1 concurrent approve per device ⇒ no current-row race; the
 * {@code @Version} optimistic lock is the belt-and-suspenders. Acquiring a
 * device lock here would invert the approve path's command→device order versus
 * PUT's device→command order and risk a deadlock.
 */
@Component
public class DisplayPolicyApprovalListener {

    private final EndpointDisplayPolicyRevisionRepository revisionRepository;
    private final EndpointDisplayPolicyRepository policyRepository;
    private final Clock clock;

    public DisplayPolicyApprovalListener(
            EndpointDisplayPolicyRevisionRepository revisionRepository,
            EndpointDisplayPolicyRepository policyRepository,
            Clock clock) {
        this.revisionRepository = revisionRepository;
        this.policyRepository = policyRepository;
        this.clock = clock;
    }

    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onCommandApprovalDecided(CommandApprovalDecidedEvent event) {
        if (event.commandType() != CommandType.SET_DISPLAY_POLICY) {
            return;
        }
        if (event.decision() != ApprovalDecision.APPROVE) {
            // REJECT (or any non-approve): the proposal is dead; current truth
            // is left untouched so a rejected policy never takes effect.
            return;
        }

        // Source of truth is the REVISION, not the command payload (Codex
        // must-fix #4). ux_edpr_command guarantees ≤1 row per command; treat any
        // other cardinality as a data-integrity error and roll the approve back.
        List<EndpointDisplayPolicyRevision> revisions =
                revisionRepository.findByTenantIdAndCommandId(event.tenantId(), event.commandId());
        if (revisions.size() != 1) {
            throw new IllegalStateException(
                    "Display-policy approval integrity error: expected exactly 1 revision for command "
                            + event.commandId() + " but found " + revisions.size() + ".");
        }
        EndpointDisplayPolicyRevision revision = revisions.get(0);

        EndpointDisplayPolicy current = policyRepository
                .findByTenantIdAndDeviceId(event.tenantId(), revision.getDeviceId())
                .orElseGet(EndpointDisplayPolicy::new);

        boolean isNew = current.getId() == null;
        Instant now = Instant.now(clock);

        current.setTenantId(revision.getTenantId());
        current.setDeviceId(revision.getDeviceId());
        current.setScopeType(EndpointDisplayPolicyRevision.SCOPE_DEVICE);
        current.setOperation(revision.getOperation());
        current.setCurrentRevisionId(revision.getId());
        current.setPolicyHashSha256(revision.getPolicyHashSha256());
        current.setLastUpdatedBySubject(event.decidedBySubject());
        current.setLastUpdatedAt(now);
        if (isNew) {
            current.setCreatedBySubject(revision.getCreatedBySubject());
            current.setCreatedAt(now);
        }

        if (revision.getOperation() == DisplayPolicyOperation.CLEAR) {
            current.clearSnapshot();
            current.setClearedAt(now);
            current.setClearedBySubject(event.decidedBySubject());
        } else {
            // ENFORCE: active managed policy (cleared_at must be NULL per
            // ck_edp_operation_cleared_state); copy the desired-state snapshot.
            current.setClearedAt(null);
            current.setClearedBySubject(null);
            current.setScreensaverEnabled(revision.getScreensaverEnabled());
            current.setScreensaverTimeoutSeconds(revision.getScreensaverTimeoutSeconds());
            current.setScreensaverSecure(revision.getScreensaverSecure());
            current.setScreensaverScrPath(revision.getScreensaverScrPath());
            current.setWallpaperEnabled(revision.getWallpaperEnabled());
            current.setWallpaperStyle(revision.getWallpaperStyle());
            current.setWallpaperUserCannotChange(revision.getWallpaperUserCannotChange());
            current.setWallpaperAssetRef(revision.getWallpaperAssetRef());
            current.setWallpaperAssetSha256(revision.getWallpaperAssetSha256());
            current.setWallpaperContentType(revision.getWallpaperContentType());
        }

        policyRepository.save(current);
    }
}
