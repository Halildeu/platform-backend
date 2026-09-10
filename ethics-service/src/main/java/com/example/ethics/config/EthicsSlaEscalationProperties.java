package com.example.ethics.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-301 — when a missed deadline stops being a line in a list and becomes an event (#882).
 *
 * <p>A breach is a fact the clock computes; an escalation is a fact the organisation
 * <em>records</em>: on this date, this obligation on this case was this far overdue and the
 * matter was raised to level N. The steps say how far past the statutory deadline each level
 * sits. They are offsets <strong>after</strong> the deadline, never before it and never
 * instead of it — the deadline stays where EU 2019/1937 put it, and no step can be
 * configured to soften it: level 1 at {@code PT0S} means "the moment the law was missed".
 *
 * <p><strong>Owner-supplied, absent by default.</strong> Which levels exist and how quickly
 * they follow one another is the organisation's escalation policy (charter: "escalation
 * matrisi"), not the platform's. With no configuration nothing is recorded and nothing
 * changes — the same posture as the working calendar.
 *
 * <p>Capped and ordered because a policy that could not be read back is not a policy: at most
 * five levels, strictly increasing, none further out than the longest legal window. A
 * misordered list fails at startup rather than producing level 3 before level 2 at 03:00.
 */
@ConfigurationProperties(prefix = "ethics.sla.escalation")
public record EthicsSlaEscalationProperties(boolean enabled, List<Duration> steps) {

    /** More levels than this is a list nobody escalates through; it is a calendar. */
    public static final int MAX_LEVELS = 5;

    /** A step past the longest statutory window would escalate what is already a closed matter. */
    public static final Duration STEP_MAXIMUM = EthicsSlaProperties.FEEDBACK_LEGAL_MAXIMUM;

    public EthicsSlaEscalationProperties {
        // Not List#contains(null): an immutable list throws NPE on that question.
        if (steps != null) {
            for (Duration step : steps) {
                if (step == null) {
                    throw new IllegalArgumentException(
                            "ethics.sla.escalation.steps contains an empty entry; every level needs an offset");
                }
            }
        }
        steps = (steps == null || steps.isEmpty()) ? List.of(Duration.ZERO) : List.copyOf(steps);
        if (steps.size() > MAX_LEVELS) {
            throw new IllegalArgumentException(
                    "ethics.sla.escalation.steps allows at most " + MAX_LEVELS
                            + " levels; got " + steps.size());
        }
        Duration previous = null;
        for (int i = 0; i < steps.size(); i++) {
            Duration step = steps.get(i);
            if (step == null || step.isNegative()) {
                throw new IllegalArgumentException(
                        "ethics.sla.escalation.steps[" + i + "] must be a non-negative offset "
                                + "after the statutory deadline; a step before the deadline "
                                + "would escalate an obligation that is not yet missed");
            }
            if (step.compareTo(STEP_MAXIMUM) > 0) {
                throw new IllegalArgumentException(
                        "ethics.sla.escalation.steps[" + i + "]=" + step
                                + " exceeds " + STEP_MAXIMUM + ", the longest legal window");
            }
            if (previous != null && step.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "ethics.sla.escalation.steps must be strictly increasing so that level "
                                + (i + 1) + " follows level " + i + "; got " + steps);
            }
            previous = step;
        }
    }

    /** How many levels the policy defines. Level numbers are 1-based. */
    public int levels() {
        return steps.size();
    }

    /**
     * The highest level reached for an obligation that fell due at {@code dueAt} and is still
     * unmet at {@code now}; 0 when the deadline has not passed. Pure arithmetic on the two
     * instants — there is no parameter through which a pause could reach it.
     */
    public int levelReached(java.time.Instant dueAt, java.time.Instant now) {
        if (dueAt == null || now == null || !now.isAfter(dueAt)) return 0;
        int level = 0;
        for (Duration step : steps) {
            if (now.isAfter(dueAt.plus(step))) level++;
            else break;
        }
        return level;
    }
}
