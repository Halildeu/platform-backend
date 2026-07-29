package com.example.ethics.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * ES-301 — the two deadlines EU 2019/1937 puts on a whistleblowing channel.
 *
 * <p>Art. 9(1)(b): acknowledge receipt to the reporter within seven days.
 * Art. 9(1)(f): give the reporter feedback within three months.
 *
 * <p>These are configurable because an organisation may commit to answering faster than
 * the law requires, and some do. They are <em>capped</em> because it may not commit to
 * answering slower: a config that allowed thirty days would turn a legal maximum into a
 * setting, and the breach would then be invisible by configuration rather than by
 * oversight. Exceeding a cap fails at startup rather than at audit time.
 *
 * <p>Measured on the test cell before this existed: 167 cases, 32 acknowledged, and
 * <strong>49 already past the seven-day mark</strong> with no acknowledgement. Nothing
 * anywhere reported that. The deadline was computed in the browser from {@code createdAt},
 * so it existed only while someone had a case open on screen — it could not be queried,
 * alerted on, or counted.
 */
@ConfigurationProperties(prefix = "ethics.sla")
public record EthicsSlaProperties(Duration acknowledgementWithin, Duration feedbackWithin) {

    /** EU 2019/1937 art. 9(1)(b). */
    public static final Duration ACKNOWLEDGEMENT_LEGAL_MAXIMUM = Duration.ofDays(7);

    /** EU 2019/1937 art. 9(1)(f). Three months, read as 90 days. */
    public static final Duration FEEDBACK_LEGAL_MAXIMUM = Duration.ofDays(90);

    public EthicsSlaProperties {
        acknowledgementWithin = defaulted(acknowledgementWithin, ACKNOWLEDGEMENT_LEGAL_MAXIMUM);
        feedbackWithin = defaulted(feedbackWithin, FEEDBACK_LEGAL_MAXIMUM);
        requireWithinLegalMaximum("ethics.sla.acknowledgement-within",
                acknowledgementWithin, ACKNOWLEDGEMENT_LEGAL_MAXIMUM);
        requireWithinLegalMaximum("ethics.sla.feedback-within",
                feedbackWithin, FEEDBACK_LEGAL_MAXIMUM);
    }

    private static Duration defaulted(Duration configured, Duration fallback) {
        return configured == null ? fallback : configured;
    }

    private static void requireWithinLegalMaximum(String key, Duration configured, Duration maximum) {
        if (configured.isNegative() || configured.isZero()) {
            throw new IllegalArgumentException(
                    key + " must be positive; a zero or negative deadline is not a deadline");
        }
        if (configured.compareTo(maximum) > 0) {
            throw new IllegalArgumentException(
                    key + "=" + configured + " exceeds the legal maximum " + maximum
                            + "; the channel may promise to answer sooner than the law requires, never later");
        }
    }
}
