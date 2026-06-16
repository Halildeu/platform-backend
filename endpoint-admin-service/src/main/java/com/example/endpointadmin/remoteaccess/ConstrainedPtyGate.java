package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 D-3 — the single constrained-PTY admission point that composes the two gates so the transport
 * cannot run one without the other (Codex 019eb874): a command line is permitted ONLY if {@link
 * PtyCommandGuard} (D-2: safe character class + bare-name command allowlist) AND {@link PtyArgumentPolicy}
 * (D-3: closed per-argument flag policy) both ALLOW it. The C/D PTY transport calls THIS, not the two gates
 * separately — making the layering a real transitive control, not two independent unit-tested objects.
 *
 * <p>Evaluation is LAYERED and fail-closed: the command guard runs first (no point vetting arguments of a line
 * that isn't even a safe, allowlisted command); only if it ALLOWs does the argument policy run. When the
 * command guard refuses, the argument policy is NOT consulted and {@link Result#argument()} is {@code null}
 * (the "not-consulted" marker, mirroring the cert-gate's {@code storeOutcome=null} precedent) — so an audit
 * log can tell "refused at the syntax/allowlist layer" from "refused at the argument layer".
 */
public final class ConstrainedPtyGate {

    /** The layered outcome: {@code permitted} iff both gates ALLOW. {@code argument} is {@code null} when the
     *  command gate refused first (argument policy not consulted). */
    public record Result(boolean permitted,
                         PtyCommandGuard.Decision command,
                         PtyArgumentPolicy.Decision argument) {
    }

    private final PtyCommandGuard commandGuard;
    private final PtyArgumentPolicy argumentPolicy;

    public ConstrainedPtyGate(PtyCommandGuard commandGuard, PtyArgumentPolicy argumentPolicy) {
        if (commandGuard == null || argumentPolicy == null) {
            throw new IllegalArgumentException("both gates are required");
        }
        this.commandGuard = commandGuard;
        this.argumentPolicy = argumentPolicy;
    }

    /** The pilot gate — composes the D-2 and D-3 pilot defaults over the same five commands (the shell-builtin
     *  {@code ver} was dropped from both, board #1613). */
    public static final ConstrainedPtyGate PILOT = new ConstrainedPtyGate(
            new PtyCommandGuard(PtyCommandGuard.PILOT_DEFAULT_ALLOWLIST),
            PtyArgumentPolicy.PILOT_DEFAULT_POLICY);

    /** Full layered verdict for audit. Total, fail-closed. */
    public Result evaluate(String commandLine) {
        PtyCommandGuard.Decision command = commandGuard.decide(commandLine);
        if (command != PtyCommandGuard.Decision.ALLOWED) {
            return new Result(false, command, null);   // argument policy not consulted
        }
        PtyArgumentPolicy.Decision argument = argumentPolicy.decide(commandLine);
        return new Result(argument == PtyArgumentPolicy.Decision.ALLOWED, command, argument);
    }

    /** Convenience boolean — the transport's go/no-go. */
    public boolean permits(String commandLine) {
        return evaluate(commandLine).permitted();
    }
}
