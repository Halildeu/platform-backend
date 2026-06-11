package com.example.endpointadmin.remoteaccess;

import java.util.Set;

/**
 * Faz 22.6 capability classes (ADR-0033 §7, ADR-0034 D8).
 *
 * <p>The capability set is <b>broker-computed</b> — the agent's advertised capabilities are a
 * signal only, never authority (false-advertising guard, ADR-0033 §4). Each capability is a
 * distinct abuse/exfiltration/privacy vector; the #1388 pilot is "narrowest-first".
 *
 * <p>Owner decision (ADR-0034 D8, 2026-06-11): the interactive remote session pilot is limited
 * to {@link #VIEW_ONLY} + {@link #CONSTRAINED_PTY}. The riskier capabilities below stay OFF for
 * the interactive pilot. {@code FILE_COPY} is approved ONLY for the non-interactive 22.8
 * offboarding/audit scenario behind scenario-based dual-approval + chain-of-custody — it is NOT
 * a free in-session capability and is therefore NOT in {@link #PILOT_ALLOWED}.
 */
public enum RemoteSessionCapability {

    /** View-only screen-share, no input. Lowest risk. */
    VIEW_ONLY(true),
    /** Allowlisted constrained PTY (command allowlist). */
    CONSTRAINED_PTY(true),

    // ---- OFF for the interactive pilot (ADR-0033 §7 exfil controls; ADR-0034 D8) ----
    /** Full RDP / desktop control — broadest action surface. */
    FULL_RDP(false),
    /** Free in-session file transfer. OFF for pilot; 22.8 offboarding/audit copy is a separate scenario-gated plane. */
    FILE_TRANSFER(false),
    /** Clipboard sync — covert exfil channel invisible to screen recording. OFF (needs clipboard-audit + redaction if ever enabled). */
    CLIPBOARD_SYNC(false),
    /** Operator credential entry/injection. OFF — no reusable admin credential ever handed to the agent. */
    CREDENTIAL_ENTRY(false),
    /** Privilege elevation. OFF. */
    ELEVATION(false),
    /** Generalized port-forward — turns the bounded session into a network pivot. OFF. */
    PORT_FORWARD(false),
    /** Background/unattended persistence. OFF — defeats attended-only consent (ADR-0034 D6). */
    BACKGROUND_PERSISTENCE(false);

    private final boolean pilotAllowed;

    RemoteSessionCapability(boolean pilotAllowed) {
        this.pilotAllowed = pilotAllowed;
    }

    public boolean isPilotAllowed() {
        return pilotAllowed;
    }

    /** The only capabilities an interactive pilot session may be granted (ADR-0034 D8). */
    public static final Set<RemoteSessionCapability> PILOT_ALLOWED = Set.of(VIEW_ONLY, CONSTRAINED_PTY);
}
