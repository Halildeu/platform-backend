package com.example.endpointadmin.remoteaccess;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D-2 — the constrained-PTY command allowlist (ADR-0033 §7, ADR-0034 D8). Where {@link
 * RemoteOperationGuard} decides that a {@link RemoteOperation#PTY_COMMAND} is permitted at all (the session
 * holds {@link RemoteSessionCapability#CONSTRAINED_PTY}), THIS guard decides <em>which</em> command line may
 * run inside that PTY — the finer gate that makes "constrained" mean something. Pure, total, fail-closed.
 *
 * <p><b>Two layers, both fail-closed:</b>
 * <ol>
 *   <li><b>Safe character class (allowlist-by-construction):</b> the whole line may contain only
 *       {@code [A-Za-z0-9 ._:/,=+-]} — letters, digits, space, and flag/host punctuation. Every shell
 *       metacharacter ({@code ; | & < > $ ` ( ) { } [ ] ^ % ! * ? ~ " ' \ #}) and every control character
 *       (newline / tab / NUL …) is OUTSIDE the class and refused. This is an allowlist, not a denylist — a
 *       metacharacter nobody enumerated cannot slip through, and no chaining / piping / redirection /
 *       subshell / variable-or-command expansion / multi-line injection is representable.</li>
 *   <li><b>Command allowlist:</b> {@code argv[0]} (the first space-delimited token) must be a <em>bare</em>
 *       command name on the configured allowlist. A bare name is a letter then {@code [A-Za-z0-9_-]} — no
 *       path separator, drive, extension, or traversal: the PTY resolves the name against a fixed secure
 *       PATH, never an operator-supplied path ({@code ./sh}, {@code ..\\cmd.exe}, {@code /bin/sh} are all
 *       refused). Matching is case-insensitive (Windows command names are).</li>
 * </ol>
 *
 * <p><b>Executor invariant (assumed, MUST hold):</b> this character-class reasoning is sound ONLY because the
 * PTY executes each allowlisted command via a direct {@code CreateProcess}/{@code execve} with argv — NEVER
 * through a shell ({@code cmd /c …}, {@code powershell -Command …}, {@code ProcessBuilder} on a single shell
 * string). With no shell interpreter there is no metacharacter to interpret, so the only residual risk is a
 * command's OWN option parsing (handled by the allowlist below). The C/D transport that wires this guard MUST
 * honour the no-shell contract; if a command is ever spawned via a shell, this guard's guarantees are void.
 *
 * <p><b>Per-argument safety is a later slice (D-3), and the pilot set is chosen so the deferral hides no risk.</b>
 * This guard admits a command by NAME; it does not yet vet arguments. The {@link #PILOT_DEFAULT_ALLOWLIST}
 * therefore contains ONLY commands that are argument-safe even with no flag policy — commands for which NO
 * argument representable in the safe character class can write a file, reach a remote host, carry a credential,
 * execute code, or drop to an interactive sub-shell. Commands whose safety depends on the argument are
 * deliberately EXCLUDED until D-3's per-argument grammar lands, e.g.:
 * <ul>
 *   <li>{@code gpresult /X out.xml}, {@code gpresult /H out.html} — write an attacker-chosen file (host mutation);</li>
 *   <li>{@code systeminfo /S host /U user /P pass}, {@code tasklist /S …}, {@code getmac /S …},
 *       {@code driverquery /S …} — the {@code /S /U /P} family turns a local probe into REMOTE recon /
 *       lateral movement and puts a credential on the command line (which the WORM recorder would persist —
 *       a Phase C / KVKK m.12 leak);</li>
 *   <li>{@code nslookup} with no/served argument — drops into an interactive resolver sub-shell;</li>
 *   <li>{@code ipconfig /release|/renew|/flushdns}, {@code sc stop|start|delete}, {@code net user},
 *       {@code route add}, {@code arp -d}, {@code wmic process call create} — mutate host / network state.</li>
 * </ul>
 * D-3 re-admits these with an explicit allowed-flag set per command; until then the gate stays narrowest-first.
 */
public final class PtyCommandGuard {

    /** The explicit, auditable outcome of one constrained-PTY command check. */
    public enum Decision {
        /** {@code argv[0]} is a bare allowlisted command and the line carries no unsafe syntax. */
        ALLOWED(true),
        /** Syntax is clean but {@code argv[0]} is not on the allowlist. */
        DENIED_NOT_ALLOWLISTED(false),
        /** The line contains a shell metacharacter, control character, or a non-bare command name → it is
         *  refused BEFORE tokenisation (no chaining / expansion / redirection / path-escape is ever run). */
        DENIED_UNSAFE_SYNTAX(false),
        /** Null / blank / over-length input → fail-closed. */
        DENIED_MALFORMED(false);

        private final boolean allowed;

        Decision(boolean allowed) {
            this.allowed = allowed;
        }

        public boolean allowed() {
            return allowed;
        }
    }

    /** Max accepted command-line length — pathological input is refused rather than scanned. */
    static final int MAX_LENGTH = 4096;

    /** The minimal safe character class a constrained-PTY command line may use (see class javadoc). */
    private static final Pattern SAFE_LINE = Pattern.compile("^[A-Za-z0-9 ._:/,=+-]+$");

    /** A bare command name: a letter followed by letters / digits / {@code _ -}. No path / drive / extension. */
    private static final Pattern COMMAND_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]*$");

    /**
     * The pilot default constrained-PTY allowlist (ADR-0034 D8): the narrowest-first set of read-only Windows
     * diagnostics that are argument-safe with NO flag policy — for each, no argument representable in the safe
     * character class can write a file, reach a remote host, carry a credential, exec code, or open an
     * interactive sub-shell ({@code whoami}/{@code netstat} have no {@code /S} remote flag and no file-output;
     * {@code ping}/{@code tracert} only probe; {@code hostname}/{@code ver} just print). Commands that need an
     * argument policy to be safe ({@code gpresult}, {@code systeminfo}, {@code tasklist}, {@code getmac},
     * {@code driverquery}, {@code nslookup}, and the host/network-mutating families) are intentionally ABSENT
     * — a bare command-name allow cannot make them safe; D-3 re-admits them with explicit allowed-flag sets.
     */
    public static final Set<String> PILOT_DEFAULT_ALLOWLIST = Set.of(
            "hostname", "whoami", "ver", "netstat", "ping", "tracert");

    private final Set<String> allowlist;

    /**
     * @param allowlist bare command names (matched case-insensitively); each entry MUST itself be a bare name
     *                  ({@link #COMMAND_NAME}) or construction fails fast — a metacharacter or path in the
     *                  allowlist is a configuration bug that must never silently widen the gate.
     */
    public PtyCommandGuard(Set<String> allowlist) {
        if (allowlist == null) {
            throw new IllegalArgumentException("allowlist must not be null");
        }
        this.allowlist = allowlist.stream()
                .map(entry -> {
                    if (entry == null || !COMMAND_NAME.matcher(entry).matches()) {
                        throw new IllegalArgumentException("illegal constrained-PTY allowlist entry: " + entry);
                    }
                    return entry.toLowerCase(Locale.ROOT);
                })
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * Decide whether {@code commandLine} may run in the constrained PTY. Fail-closed: anything not provably
     * a bare allowlisted command with safe syntax is denied. Total — never throws, every input maps to a
     * {@link Decision}.
     */
    public Decision decide(String commandLine) {
        if (commandLine == null || commandLine.isBlank() || commandLine.length() > MAX_LENGTH) {
            return Decision.DENIED_MALFORMED;
        }
        if (!SAFE_LINE.matcher(commandLine).matches()) {
            return Decision.DENIED_UNSAFE_SYNTAX; // a metacharacter / control char → never tokenised or run
        }
        String argv0 = commandLine.trim().split(" +", 2)[0];
        if (!COMMAND_NAME.matcher(argv0).matches()) {
            return Decision.DENIED_UNSAFE_SYNTAX; // a path / drive / extension / traversal in the command name
        }
        return allowlist.contains(argv0.toLowerCase(Locale.ROOT))
                ? Decision.ALLOWED
                : Decision.DENIED_NOT_ALLOWLISTED;
    }
}
