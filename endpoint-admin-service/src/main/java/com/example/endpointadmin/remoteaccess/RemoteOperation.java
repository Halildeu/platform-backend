package com.example.endpointadmin.remoteaccess;

import java.util.Set;

import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.CLIPBOARD_SYNC;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.CONSTRAINED_PTY;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.CREDENTIAL_ENTRY;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.ELEVATION;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.FILE_TRANSFER;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.FULL_RDP;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.PORT_FORWARD;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.VIEW_ONLY;

/**
 * Faz 22.6 D-1 — the discrete operations an operator can attempt inside a live remote session, each mapped to
 * the {@link RemoteSessionCapability}(ies) that would permit it (ADR-0033 §7, ADR-0034 D8). The
 * {@link RemoteOperationGuard} ALLOWs an operation only when the session's granted capability set intersects
 * its {@link #permittedBy}; an operation permitted by no granted capability is DENIED (fail-closed). This is
 * the VIEW_ONLY / constrained-PTY enforcement at the OPERATION level — distinct from (and tighter than) the
 * grant-time capability decision: a VIEW_ONLY session can only {@link #SCREEN_VIEW}; every input / clipboard /
 * file / pivot / credential / elevation operation is refused.
 */
public enum RemoteOperation {

    /** Render the target's screen to the operator. The lowest-risk operation — any viewing capability allows it. */
    SCREEN_VIEW(Set.of(VIEW_ONLY, CONSTRAINED_PTY, FULL_RDP)),
    /** Send a keystroke. Only an interactive capability (constrained PTY or full RDP) permits it. */
    KEYBOARD_INPUT(Set.of(CONSTRAINED_PTY, FULL_RDP)),
    /** Send pointer input. Full desktop control only. */
    MOUSE_INPUT(Set.of(FULL_RDP)),
    /** Run an allowlisted PTY command. */
    PTY_COMMAND(Set.of(CONSTRAINED_PTY)),
    /** Read the target clipboard into the operator session (covert exfil channel). */
    CLIPBOARD_COPY(Set.of(CLIPBOARD_SYNC)),
    /** Write the operator clipboard to the target. */
    CLIPBOARD_PASTE(Set.of(CLIPBOARD_SYNC)),
    /** Push a file to the target. */
    FILE_UPLOAD(Set.of(FILE_TRANSFER)),
    /** Pull a file from the target (exfil). */
    FILE_DOWNLOAD(Set.of(FILE_TRANSFER)),
    /** Open a forwarded port (turns the session into a network pivot). */
    OPEN_PORT_FORWARD(Set.of(PORT_FORWARD)),
    /** Inject an operator credential into the target. */
    CREDENTIAL_INJECT(Set.of(CREDENTIAL_ENTRY)),
    /** Elevate privilege on the target. */
    PRIVILEGE_ELEVATE(Set.of(ELEVATION));

    private final Set<RemoteSessionCapability> permittedBy;

    RemoteOperation(Set<RemoteSessionCapability> permittedBy) {
        this.permittedBy = permittedBy;
    }

    /** The capabilities, ANY of which permits this operation. */
    public Set<RemoteSessionCapability> permittedBy() {
        return permittedBy;
    }
}
