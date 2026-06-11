package com.example.endpointadmin.remoteaccess;

import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D-4 — the secret / PII redaction engine (ADR-0033 §7, ADR-0034 D8; KVKK m.12). A single
 * deterministic redactor the Phase-C WORM recorder and the operator-visible PTY-output stream both call at
 * the C/D chokepoint, so a credential / key / PII string is masked BEFORE it is shown to the operator and
 * BEFORE it is persisted for 7 years. It closes the D-2 follow-up "raw-command-line log sanitization" with a
 * real implementation, and consumes D-3's {@link PtyArgumentPolicy#sensitiveValueFlags(String)} metadata for
 * exact structured redaction.
 *
 * <p><b>This is a DISPLAY / AUDIT redactor — it NEVER gates execution</b> (the D-1/D-2/D-3 gates do). So
 * over-redaction is SAFE: it degrades readability, never security. The engine is therefore fail-closed toward
 * MORE masking — on oversized input or any internal error it emits a fully-masked fallback, never the raw text.
 *
 * <p><b>Two entry points (Codex 019eb874):</b>
 * <ul>
 *   <li><b>Structured</b> {@link #redactCommandLine(String, Set)} — exact, deterministic: mask the VALUE token
 *       after each flag named in the caller-supplied sensitive set (the recorder resolves the set from D-3's
 *       policy; the redactor stays policy-agnostic). No guessing — when a credential-bearing flag is
 *       re-admitted (D-4-later) its value is masked by construction.</li>
 *   <li><b>Free-form</b> {@link #redactText(String)} — a SMALL set of HIGH-CONFIDENCE, low-false-positive
 *       patterns only (JWT, PEM private-key block, AWS key, {@code Authorization: Bearer}, {@code password=}/
 *       connection-string, URL userinfo, and — under the PII profile — Turkish TCKN with checksum validation,
 *       Turkish IBAN, email). Fuzzy Shannon-entropy "looks-like-a-token" detection is DELIBERATELY OUT OF
 *       SCOPE — it is lossy and is an explicit later slice (D-5), NOT a silent gap.</li>
 * </ul>
 *
 * <p><b>Category visibility:</b> {@link LabelMode#CATEGORY} emits {@code [REDACTED:CREDENTIAL]} for audit
 * triage; {@link LabelMode#OPAQUE} emits a bare {@code [REDACTED]} (the category survives only in the
 * {@link RedactionResult#hits()} metric channel) for logs that may be visible to lower-privilege viewers.
 */
public final class SecretRedactor {

    /** The class of redacted material — kept separate for metering and KVKK reporting. */
    public enum Category { CREDENTIAL, KEY_MATERIAL, PII }

    /** Which categories are active. {@code KEY_MATERIAL} is a secret, not PII, so it is always on. */
    public enum Profile {
        CREDENTIAL_ONLY(EnumSet.of(Category.CREDENTIAL, Category.KEY_MATERIAL)),
        CREDENTIAL_AND_PII(EnumSet.allOf(Category.class));

        private final Set<Category> active;

        Profile(Set<Category> active) {
            this.active = active;
        }

        public Set<Category> activeCategories() {
            return active;
        }
    }

    /** Whether the mask names its category in the text, or only in the metric channel. */
    public enum LabelMode { CATEGORY, OPAQUE }

    /** Redacted text + per-category hit counts (the counts NEVER carry the secret — only how many were masked). */
    public record RedactionResult(String maskedText, Map<Category, Integer> hits) {

        public RedactionResult {
            hits = hits == null ? Map.of() : Map.copyOf(hits);
        }

        public int total() {
            return hits.values().stream().mapToInt(Integer::intValue).sum();
        }
    }

    static final int MAX_LEN = 1_000_000;
    private static final String OPAQUE_MASK = "[REDACTED]";

    /** A redaction rule: a category, a linear (ReDoS-safe) pattern, and a rebuild that masks the secret span. */
    private record Rule(Category category, Pattern pattern, BiFunction<MatchResult, String, String> rebuild) {}

    /** All rules, in apply order: CREDENTIAL context first (so a Bearer-JWT is masked as a credential before
     *  the JWT rule sees it), then KEY_MATERIAL, then PII. Each pattern is linear — no nested quantifiers. */
    private static final List<Rule> ALL_RULES = List.of(
            // ---- CREDENTIAL ----
            new Rule(Category.CREDENTIAL,
                    Pattern.compile("(?i)\\b(password|passwd|pwd|pass)(\\s*[=:]\\s*)([^\\s;,&\"']{1,256})"),
                    (m, mask) -> m.group(1) + m.group(2) + mask),
            new Rule(Category.CREDENTIAL,
                    Pattern.compile("(?i)(Bearer\\s+)([A-Za-z0-9._~+/=-]{8,})"),
                    (m, mask) -> m.group(1) + mask),
            new Rule(Category.CREDENTIAL,
                    // anchored to the Authorization header so the common English word "Basic" is not a false positive (Codex)
                    Pattern.compile("(?i)(Authorization:\\s*Basic\\s+)([A-Za-z0-9+/]{4,}={0,2})"),
                    (m, mask) -> m.group(1) + mask),
            new Rule(Category.CREDENTIAL,
                    Pattern.compile("([A-Za-z][A-Za-z0-9+.-]*://)([^@/\\s:]+:[^@/\\s]+)@"),
                    (m, mask) -> m.group(1) + mask + "@"),
            // ---- KEY_MATERIAL ----
            new Rule(Category.KEY_MATERIAL,
                    Pattern.compile("\\beyJ[A-Za-z0-9_-]{6,}\\.eyJ[A-Za-z0-9_-]{6,}\\.[A-Za-z0-9_-]{6,}"),
                    (m, mask) -> mask),
            new Rule(Category.KEY_MATERIAL,
                    Pattern.compile("(?s)-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----.*?-----END [A-Z0-9 ]*PRIVATE KEY-----"),
                    (m, mask) -> mask),
            new Rule(Category.KEY_MATERIAL,
                    Pattern.compile("\\b(?:AKIA|ASIA)[0-9A-Z]{16}\\b"),   // long-term keys + STS session tokens (Codex)
                    (m, mask) -> mask),
            // ---- PII (KVKK) ----
            new Rule(Category.PII,
                    Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,24}\\b"),
                    (m, mask) -> mask),
            new Rule(Category.PII,
                    Pattern.compile("\\bTR\\d{2}[A-Z0-9]{22}\\b"),
                    (m, mask) -> mask),
            new Rule(Category.PII,
                    Pattern.compile("\\b[1-9][0-9]{10}\\b"),
                    (m, mask) -> isValidTckn(m.group()) ? mask : m.group()));   // only a checksum-valid TCKN

    private final LabelMode labelMode;
    private final List<Rule> rules;

    public SecretRedactor(Profile profile, LabelMode labelMode) {
        Objects.requireNonNull(profile, "profile");
        this.labelMode = Objects.requireNonNull(labelMode, "labelMode");
        Set<Category> active = profile.activeCategories();
        this.rules = ALL_RULES.stream().filter(r -> active.contains(r.category())).toList();
    }

    /** KVKK-conservative default: redact credentials, key material, AND PII; category-labelled for audit. */
    public static final SecretRedactor DEFAULT = new SecretRedactor(Profile.CREDENTIAL_AND_PII, LabelMode.CATEGORY);

    private String mask(Category category) {
        return labelMode == LabelMode.CATEGORY ? "[REDACTED:" + category.name() + "]" : OPAQUE_MASK;
    }

    /**
     * Redact free-form text (PTY output) — apply every active high-confidence pattern, progressively (a later
     * pattern never re-matches inside an earlier mask). Total, fail-closed: null → empty; oversized or any
     * error → fully masked (never the raw text).
     */
    public RedactionResult redactText(String text) {
        if (text == null) {
            return new RedactionResult("", Map.of());
        }
        if (text.length() > MAX_LEN) {
            return new RedactionResult(OPAQUE_MASK, Map.of());
        }
        try {
            Map<Category, Integer> hits = new LinkedHashMap<>();
            String current = text;
            for (Rule rule : rules) {
                String maskToken = mask(rule.category());
                Matcher matcher = rule.pattern().matcher(current);
                StringBuilder sb = new StringBuilder();
                while (matcher.find()) {
                    String replacement = rule.rebuild().apply(matcher.toMatchResult(), maskToken);
                    if (!replacement.equals(matcher.group())) {
                        hits.merge(rule.category(), 1, Integer::sum);
                    }
                    matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
                }
                matcher.appendTail(sb);
                current = sb.toString();
            }
            return new RedactionResult(current, hits);
        } catch (RuntimeException e) {
            return new RedactionResult(OPAQUE_MASK, Map.of()); // a redactor that throws must NOT leak the raw text
        }
    }

    /**
     * Structured redaction of a command line: mask the VALUE token following each flag named in
     * {@code sensitiveFlags} (matched case-insensitively). Policy-agnostic — the caller resolves the set
     * (e.g. {@code policy.sensitiveValueFlags(command)}). Total, fail-closed.
     */
    public String redactCommandLine(String commandLine, Set<String> sensitiveFlags) {
        if (commandLine == null) {
            return "";
        }
        if (commandLine.length() > MAX_LEN) {
            return OPAQUE_MASK;
        }
        try {
            Set<String> sensitive = sensitiveFlags == null ? Set.of()
                    : sensitiveFlags.stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            String[] tokens = commandLine.trim().split(" +");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length; i++) {
                if (i > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i]);
                if (sensitive.contains(tokens[i].toLowerCase(Locale.ROOT)) && i + 1 < tokens.length) {
                    sb.append(' ').append(mask(Category.CREDENTIAL));
                    i++;   // the value is masked, not emitted
                }
            }
            return sb.toString();
        } catch (RuntimeException e) {
            return OPAQUE_MASK;
        }
    }

    /** Turkish national identity number (TCKN) checksum — cuts the false positives of a bare 11-digit match. */
    private static boolean isValidTckn(String s) {
        if (s.length() != 11) {
            return false;
        }
        int[] d = new int[11];
        for (int i = 0; i < 11; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            d[i] = c - '0';
        }
        if (d[0] == 0) {
            return false;
        }
        int oddSum = d[0] + d[2] + d[4] + d[6] + d[8];
        int evenSum = d[1] + d[3] + d[5] + d[7];
        int check10 = ((oddSum * 7) - evenSum) % 10;
        if (check10 < 0) {
            check10 += 10;
        }
        if (check10 != d[9]) {
            return false;
        }
        int total = 0;
        for (int i = 0; i < 10; i++) {
            total += d[i];
        }
        return (total % 10) == d[10];
    }
}
