package com.example.ethics.model;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Faz 35 ES-301A — the case lifecycle, as a contract rather than a convention.
 *
 * <p>The previous vocabulary was three strings checked inline at the one place that
 * happened to write them, with no transition rule at all: anyone holding
 * {@code case_handler} could move a case from {@code CLOSED} back to {@code NEW} and
 * erase the fact that it had ever concluded. The stages here follow ISO 37002
 * (receive, assess, address, conclude), and the transitions are enumerated so that
 * "which moves are legal" is a thing tests can read rather than a thing reviewers
 * have to reconstruct from control flow.
 *
 * <p>Acknowledgement is <em>not</em> a status. A case can be acknowledged and under
 * assessment at the same time, and EU 2019/1937 art. 9(1)(b) sets a deadline on the
 * act, not on a stage. It lives on the case as {@code acknowledged_at}, stamped by
 * the system when the reporter is first written to — see {@code V9__case_lifecycle.sql}
 * for why it is not settable on its own.
 *
 * <p>This vocabulary is internal. What a reporter sees must be a narrower, steadier
 * projection derived from it, not this enum widened into a public contract — otherwise
 * every future refinement of how staff work becomes a breaking change for people
 * outside the organisation.
 */
public final class CaseLifecycle {

    public static final String NEW = "NEW";
    public static final String ASSESSING = "ASSESSING";
    public static final String INVESTIGATING = "INVESTIGATING";
    public static final String CLOSED = "CLOSED";

    public static final Set<String> STATUSES = Set.of(NEW, ASSESSING, INVESTIGATING, CLOSED);

    /**
     * Findings a case may conclude with. {@code WITHDRAWN} records that the reporter
     * stepped back <em>and</em> that the organisation chose not to pursue it further;
     * it is a decision staff take, never an automatic consequence of withdrawal,
     * because a withdrawn report does not by itself end the duty to look into it.
     * {@code REFERRED} likewise closes a file only when this organisation has reasoned
     * its way to handing it on — passing a case to another team is not a conclusion.
     */
    public static final Set<String> OUTCOMES = Set.of(
            "SUBSTANTIATED", "PARTIALLY_SUBSTANTIATED", "UNSUBSTANTIATED",
            "OUT_OF_SCOPE", "REFERRED", "WITHDRAWN");

    /**
     * Accepted on write, never stored. The manager UI in production still sends
     * {@code IN_REVIEW}; refusing it outright would break a live button before the
     * front end can be updated. Removed once nothing sends it — tracked as the last
     * step of this slice, not left to drift.
     */
    public static final Map<String, String> DEPRECATED_WRITE_ALIASES = Map.of("IN_REVIEW", ASSESSING);

    /**
     * Legal moves. Forward only, plus one way back: a closed case may be reopened into
     * {@code ASSESSING}, which keeps the reopening visible as a reassessment instead of
     * pretending the case is new. {@code CLOSED -> NEW} is absent on purpose.
     */
    private static final Map<String, Set<String>> ALLOWED = Map.of(
            NEW, Set.of(ASSESSING, CLOSED),
            ASSESSING, Set.of(INVESTIGATING, CLOSED),
            INVESTIGATING, Set.of(CLOSED),
            CLOSED, Set.of(ASSESSING));

    private CaseLifecycle() {}

    /** Uppercases and resolves a deprecated alias; returns null for anything unknown. */
    public static String canonicalStatus(String raw) {
        if (raw == null) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        String aliased = DEPRECATED_WRITE_ALIASES.getOrDefault(upper, upper);
        return STATUSES.contains(aliased) ? aliased : null;
    }

    public static String canonicalOutcome(String raw) {
        if (raw == null) return null;
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return OUTCOMES.contains(upper) ? upper : null;
    }

    /**
     * Whether {@code from -> to} may be taken. Staying put is allowed and does nothing,
     * except on a closed case: re-closing an already closed case would be a way to swap
     * one finding for another without the reopening ever appearing in the record.
     */
    public static boolean isTransitionAllowed(String from, String to) {
        if (from == null || to == null) return false;
        if (from.equals(to)) return !CLOSED.equals(from);
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static boolean isReopen(String from, String to) {
        return CLOSED.equals(from) && !CLOSED.equals(to);
    }

    /**
     * What the reporter is told, which is deliberately less than what staff see.
     *
     * <p>Three values, and they do not move when the internal vocabulary does: assessment
     * and investigation are both simply "under review" from outside. Widening this every
     * time staff gain a stage would turn internal workflow into a public contract.
     *
     * <p>It is also load-bearing rather than cosmetic. The reporter mailbox types these
     * three as a closed union with no fallback, so a fourth value would not degrade
     * gracefully — it would render the reporter's status line blank.
     */
    public static String reporterVisibleStatus(String status) {
        return switch (status) {
            case NEW -> "NEW";
            case ASSESSING, INVESTIGATING -> "IN_REVIEW";
            case CLOSED -> "CLOSED";
            default -> throw new IllegalStateException("Unsupported reporter-visible case status");
        };
    }

    /** Every legal move, as {@code FROM->TO}. The registry the contract test reads. */
    public static Set<String> allowedTransitions() {
        return ALLOWED.entrySet().stream()
                .flatMap(e -> e.getValue().stream().map(to -> e.getKey() + "->" + to))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }
}
