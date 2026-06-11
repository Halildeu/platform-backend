package com.example.endpointadmin.remoteaccess;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D-3 — the constrained-PTY per-ARGUMENT policy (ADR-0033 §7, ADR-0034 D8). {@link PtyCommandGuard}
 * (D-2) decides a command line is syntactically safe and its {@code argv[0]} is an allowlisted bare command;
 * THIS engine decides whether the command's ARGUMENTS are permitted — the layer that makes "constrained" mean
 * a CLOSED flag set, not just a command name. It closes the holes D-2 deferred: an allowlisted command can
 * still carry a dangerous flag (e.g. {@code ping -t} runs forever; a re-admitted {@code gpresult /X} writes a
 * file; a {@code /S /U /P} family puts a credential on the command line). Pure, total, fail-closed.
 *
 * <p><b>Composition:</b> a command line is permitted iff {@code PtyCommandGuard.decide(line) == ALLOWED} AND
 * {@code PtyArgumentPolicy.decide(line) == ALLOWED}. Both take the same raw line and tokenise identically
 * (trim, split on one-or-more spaces) so there is no token drift; the C/D transport MUST run both gates.
 * D-2 owns the whole-line safe character class; D-3 adds a tighter, TYPE-classified second validation of each
 * operand/value (a host is {@code [A-Za-z0-9._:-]}, a numeric flag value is a bounded integer, an enum flag
 * value is one of a fixed set) — defense-in-depth, not a duplicate of D-2.
 *
 * <p><b>Default-DENY, two-tier refusal (Codex 019eb874):</b> an unknown flag is refused
 * ({@link Decision#DENIED_UNKNOWN_FLAG}) — never passed through; a flag on the command's explicit
 * {@code forbiddenFlags} is {@link Decision#DENIED_FORBIDDEN_FLAG}, a distinct outcome the caller SHOULD
 * meter as a probe (a {@code -t}/{@code /S}/{@code /X} attempt is an operator trying a known-dangerous option,
 * worth alerting on — the {@code DENIED_NON_PILOT} precedent). A command with no policy is
 * {@link Decision#DENIED_NO_POLICY} (fail-closed).
 *
 * <p><b>Redaction metadata (Codex 019eb874):</b> a {@link ValueSpec} may be marked {@code sensitive}; the
 * recorder/audit chokepoint (Phase C / D wiring) calls {@link #sensitiveValueFlags(String)} to redact those
 * values in the WORM record (so a re-admitted credential-bearing flag never persists in clear — KVKK m.12).
 * The pilot table marks none sensitive; D-4 populates it as it re-admits the richer command families.
 */
public final class PtyArgumentPolicy {

    /** The explicit, auditable outcome of one argument-policy check. */
    public enum Decision {
        /** Every flag is allowlisted, every value/operand is in range/type, and required operands are present. */
        ALLOWED(true),
        /** A flag on the command's explicit forbidden set — refused AND metered as a known-dangerous probe. */
        DENIED_FORBIDDEN_FLAG(false),
        /** A flag not on the command's allowed set — refused (default-deny, never passed through). */
        DENIED_UNKNOWN_FLAG(false),
        /** A value-flag's value is out of its integer range / not in its enum / over length. */
        DENIED_DISALLOWED_VALUE(false),
        /** A positional operand is not type-valid, is present where none is allowed, or a required one is missing. */
        DENIED_DISALLOWED_OPERAND(false),
        /** The command has no policy entry → fail-closed. */
        DENIED_NO_POLICY(false),
        /** Null / blank / over-length line, a missing value after a value-flag, or an extra token. */
        DENIED_MALFORMED(false);

        private final boolean allowed;

        Decision(boolean allowed) {
            this.allowed = allowed;
        }

        public boolean allowed() {
            return allowed;
        }
    }

    /** Whether a command accepts a positional host operand. */
    public enum OperandRule {
        /** No positional operand permitted (a trailing token → DENIED_DISALLOWED_OPERAND). */
        NONE,
        /** A single host operand is optional. */
        OPTIONAL_HOST,
        /** A single host operand is required (its absence → DENIED_DISALLOWED_OPERAND). */
        REQUIRED_HOST
    }

    static final int MAX_LINE = 4096;
    static final int MAX_VALUE_LEN = 64;

    /** A host/IP type class — tighter than D-2's whole-line class (no {@code / , = +}); covers IPv4/IPv6/DNS. */
    private static final Pattern HOST = Pattern.compile("^[A-Za-z0-9._:-]{1,253}$");
    private static final Pattern UINT = Pattern.compile("\\d{1,18}");

    /** The accepted value of a value-flag: a bounded integer range OR a fixed enum of tokens (case-insensitive). */
    public record ValueSpec(boolean integer, long min, long max, Set<String> enumValues, boolean sensitive) {

        public ValueSpec {
            enumValues = enumValues == null ? Set.of()
                    : enumValues.stream().map(v -> v.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }

        boolean accepts(String value) {
            if (value == null || value.isEmpty() || value.length() > MAX_VALUE_LEN) {
                return false;
            }
            if (integer) {
                if (!UINT.matcher(value).matches()) {
                    return false;
                }
                try {
                    long n = Long.parseLong(value);
                    return n >= min && n <= max;
                } catch (NumberFormatException e) {
                    return false;
                }
            }
            return enumValues.contains(value.toLowerCase(Locale.ROOT));
        }

        public static ValueSpec intRange(long min, long max) {
            return new ValueSpec(true, min, max, Set.of(), false);
        }

        public static ValueSpec oneOf(String... values) {
            return new ValueSpec(false, 0, 0, Set.of(values), false);
        }

        /** A copy whose content is sensitive (e.g. a credential) and MUST be redacted in the audit record.
         *  Named {@code asSensitive} to avoid colliding with the record's auto-generated {@code sensitive()} accessor. */
        public ValueSpec asSensitive() {
            return new ValueSpec(integer, min, max, enumValues, true);
        }
    }

    /** The per-command argument grammar. All flag keys are matched case-insensitively. */
    public record CommandSpec(Set<String> valuelessFlags,
                              Map<String, ValueSpec> valueFlags,
                              Set<String> forbiddenFlags,
                              OperandRule operandRule,
                              int maxOperands) {

        public CommandSpec {
            valuelessFlags = lower(valuelessFlags);
            forbiddenFlags = lower(forbiddenFlags);
            valueFlags = valueFlags == null ? Map.of()
                    : valueFlags.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                            e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
            if (operandRule == null) {
                throw new IllegalArgumentException("operandRule must not be null");
            }
        }

        private static Set<String> lower(Set<String> in) {
            return in == null ? Set.of()
                    : in.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        }
    }

    private final Map<String, CommandSpec> specs;

    public PtyArgumentPolicy(Map<String, CommandSpec> specs) {
        if (specs == null) {
            throw new IllegalArgumentException("specs must not be null");
        }
        this.specs = specs.entrySet().stream().collect(Collectors.toUnmodifiableMap(
                e -> e.getKey().toLowerCase(Locale.ROOT), Map.Entry::getValue));
    }

    /**
     * The pilot per-argument policy — the SAME six commands as {@link PtyCommandGuard#PILOT_DEFAULT_ALLOWLIST},
     * each now constrained to a closed flag set. {@code ping -t} (infinite) and the netstat refresh-interval
     * operand (also infinite) are closed; numeric flags are range-bounded ({@code ping -l} capped well below the
     * jumbo-ICMP ceiling); no command takes a remote ({@code /S /U /P}) or file-output flag (those commands are
     * not even admitted by D-2 yet). The few info-richer read-only flags kept here ({@code netstat -b},
     * {@code whoami /priv}) are pilot-acceptable (no host/network mutation, no exfil channel); D-4 may narrow
     * them under a strict least-disclosure posture without changing this engine.
     */
    public static final PtyArgumentPolicy PILOT_DEFAULT_POLICY = new PtyArgumentPolicy(Map.of(
            "hostname", new CommandSpec(Set.of(), Map.of(), Set.of(), OperandRule.NONE, 0),
            "ver", new CommandSpec(Set.of(), Map.of(), Set.of(), OperandRule.NONE, 0),
            "whoami", new CommandSpec(
                    Set.of("/all", "/groups", "/priv", "/user", "/fqdn", "/upn", "/logonid", "/nh"),
                    Map.of("/fo", ValueSpec.oneOf("csv", "table", "list")),
                    Set.of(), OperandRule.NONE, 0),
            "netstat", new CommandSpec(
                    Set.of("-a", "-n", "-o", "-r", "-s", "-e", "-b", "-q", "-f", "-y"),
                    Map.of("-p", ValueSpec.oneOf("tcp", "udp", "tcpv6", "udpv6", "ip", "ipv6", "icmp", "icmpv6")),
                    Set.of(), OperandRule.NONE, 0),
            "ping", new CommandSpec(
                    Set.of("-a", "-4", "-6", "-f"),
                    Map.of("-n", ValueSpec.intRange(1, 10),
                            "-w", ValueSpec.intRange(1, 60_000),
                            "-l", ValueSpec.intRange(1, 8_192),   // bounded payload (Codex: avoid jumbo-ICMP resource abuse)
                            "-i", ValueSpec.intRange(1, 255)),
                    Set.of("-t"),                 // CLOSE infinite ping (Codex D-2 follow-up)
                    OperandRule.REQUIRED_HOST, 1),
            "tracert", new CommandSpec(
                    Set.of("-d", "-4", "-6"),
                    Map.of("-h", ValueSpec.intRange(1, 255),
                            "-w", ValueSpec.intRange(1, 60_000)),
                    Set.of(), OperandRule.REQUIRED_HOST, 1)));

    /**
     * Decide whether the arguments of {@code commandLine} are permitted. The line is tokenised exactly as
     * {@link PtyCommandGuard} does (trim, split on one-or-more spaces); {@code argv[0]} selects the policy.
     * Total — never throws; fail-closed — anything not provably in-policy is denied.
     */
    public Decision decide(String commandLine) {
        if (commandLine == null || commandLine.isBlank() || commandLine.length() > MAX_LINE) {
            return Decision.DENIED_MALFORMED;
        }
        String[] tokens = commandLine.trim().split(" +");
        CommandSpec spec = specs.get(tokens[0].toLowerCase(Locale.ROOT));
        if (spec == null) {
            return Decision.DENIED_NO_POLICY;
        }
        return evaluate(spec, List.of(tokens).subList(1, tokens.length));
    }

    /** Flags then operands, in one pass; default-deny. */
    private Decision evaluate(CommandSpec spec, List<String> args) {
        int operandCount = 0;
        for (int i = 0; i < args.size(); i++) {
            String tok = args.get(i);
            if (tok == null || tok.isBlank() || tok.length() > MAX_VALUE_LEN) {
                return Decision.DENIED_MALFORMED;
            }
            if (isFlag(tok)) {
                String flag = tok.toLowerCase(Locale.ROOT);
                if (spec.forbiddenFlags().contains(flag)) {
                    return Decision.DENIED_FORBIDDEN_FLAG;   // metered probe
                }
                if (spec.valuelessFlags().contains(flag)) {
                    continue;
                }
                ValueSpec valueSpec = spec.valueFlags().get(flag);
                if (valueSpec == null) {
                    return Decision.DENIED_UNKNOWN_FLAG;      // default-deny
                }
                // a value-flag consumes the NEXT token as its value
                if (i + 1 >= args.size()) {
                    return Decision.DENIED_MALFORMED;         // missing value
                }
                String value = args.get(i + 1);
                if (value == null || isFlag(value) || !valueSpec.accepts(value)) {
                    return Decision.DENIED_DISALLOWED_VALUE;
                }
                i++;                                          // skip the consumed value
            } else {
                operandCount++;
                if (spec.operandRule() == OperandRule.NONE || operandCount > spec.maxOperands()) {
                    return Decision.DENIED_DISALLOWED_OPERAND;
                }
                if (!HOST.matcher(tok).matches()) {
                    return Decision.DENIED_DISALLOWED_OPERAND;
                }
            }
        }
        if (spec.operandRule() == OperandRule.REQUIRED_HOST && operandCount == 0) {
            return Decision.DENIED_DISALLOWED_OPERAND;        // required host absent
        }
        return Decision.ALLOWED;
    }

    private static boolean isFlag(String token) {
        return token.startsWith("/") || token.startsWith("-");
    }

    /** The commands this policy covers (lowercase). Must stay in sync with the D-2 allowlist. */
    public Set<String> commands() {
        return specs.keySet();
    }

    /**
     * The value-flags of {@code command} whose VALUE is sensitive and must be redacted in the audit/WORM
     * record (consumed by the Phase C/D recording chokepoint). Empty for the pilot; D-4 populates it.
     */
    public Set<String> sensitiveValueFlags(String command) {
        CommandSpec spec = command == null ? null : specs.get(command.toLowerCase(Locale.ROOT));
        if (spec == null) {
            return Set.of();
        }
        return spec.valueFlags().entrySet().stream()
                .filter(e -> e.getValue().sensitive())
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
    }
}
