package com.example.ethics.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.ethics.notification.NotificationOutboxPublisher;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ES-009 (platform-k8s-gitops#2655) — the product detects and surfaces; it does not judge.
 *
 * <p>A scanner can say "this file is malicious". A scanner cannot say "this is a crime".
 * The difference is not technical: criminality depends on jurisdiction, context and intent,
 * not on a signature match. An automatic legal verdict harms in both directions — a false
 * positive strips an innocent reporter of the very protection the channel exists to give
 * (and they never find out, because they are anonymous), while a false negative lets
 * "the system said it was clean" stand in for the human review that should have happened.
 * Both failures are silent, and silent failures are the expensive kind here.
 *
 * <p>So the pipeline's vocabulary is deliberately limited to technical outcomes, and the
 * notification surface is deliberately limited to internal recipients. This test keeps both
 * limits: it fails if an outcome that carries a legal verdict, or a notification aimed at
 * an authority, is ever added.
 *
 * <p>What it cannot enforce is in ADR-0051 §5 — retention period, hand-over path and the
 * scope of any mandatory-reporting duty are named-human decisions. Those are marked OPEN in
 * the ADR precisely so they are not quietly answered by a commit.
 */
class NoAutomaticLegalJudgementTest {

    /**
     * Words that assert a legal conclusion or an outbound report to authority. Matched on
     * whole tokens, not substrings: `POLICY` (a technical admission rule) must not be read
     * as `POLICE`, and a substring match on "legal" would flag "illegal-content" handling
     * code that is doing exactly the right thing.
     */
    private static final Set<String> VERDICT_TOKENS = Set.of(
            "crime", "criminal", "illegal", "unlawful", "offence", "offense",
            "police", "prosecutor", "authority", "authorities", "law",
            "enforcement", "report", "reported", "notify", "notification",
            "disclose", "disclosure", "mandatory", "verdict", "guilty", "judgement",
            "judgment", "csam", "terror");

    private static java.util.List<String> tokensOf(String value) {
        var tokens = new ArrayList<String>();
        for (String token : value.split("(?<!^)(?=[A-Z])|_|-")) {
            if (!token.isBlank()) {
                tokens.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return tokens;
    }

    @Test
    @DisplayName("evidence outcomes stay technical — none of them asserts a legal conclusion")
    void pipelineOutcomesCarryNoLegalVerdict() {
        var offenders = new ArrayList<String>();
        for (var outcome : EvidenceProcessor.ProcessingException.Outcome.values()) {
            if (tokensOf(outcome.name()).stream().anyMatch(VERDICT_TOKENS::contains)) {
                offenders.add(outcome.name());
            }
        }
        assertTrue(offenders.isEmpty(),
                "an evidence outcome must describe what the pipeline observed, not what the law "
                        + "concludes, but found: " + offenders
                        + ". Criminality is a jurisdiction-and-context question; a signature "
                        + "match cannot answer it (ADR-0051 §2).");

        // Pinned as a SET, not a count. A count says "expected 2 but was 4" and leaves the
        // reader to work out which one appeared; the set names it. The tripwire is the point:
        // a new outcome should make someone re-read ADR-0051 §4 and decide, deliberately,
        // that it describes an observation rather than a conclusion.
        var actual = new java.util.TreeSet<String>();
        for (var outcome : EvidenceProcessor.ProcessingException.Outcome.values()) {
            actual.add(outcome.name());
        }
        assertEquals(
                new java.util.TreeSet<>(Set.of(
                        "INTEGRITY", "POLICY", "MALICIOUS", "UNAVAILABLE", "SANITIZE_FAILED")),
                actual,
                "the evidence outcome vocabulary changed; re-read ADR-0051 §4 before widening it");
    }

    @Test
    @DisplayName("the notification surface reaches internal recipients only, never an authority")
    void notificationEventsNameNoAuthority() {
        var events = new ArrayList<String>();
        for (Field field : NotificationOutboxPublisher.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                    && Modifier.isPublic(field.getModifiers())
                    && field.getType() == String.class) {
                field.setAccessible(true);
                try {
                    events.add((String) field.get(null));
                } catch (IllegalAccessException unreachable) {
                    throw new AssertionError(unreachable);
                }
            }
        }
        assertTrue(events.contains(NotificationOutboxPublisher.NEW_REPORT)
                        && events.contains(NotificationOutboxPublisher.REPORTER_MESSAGE),
                "the known internal events must still be present — an empty scan would pass "
                        + "this test while proving nothing");

        var offenders = events.stream()
                .filter(event -> tokensOf(event).stream().anyMatch(VERDICT_TOKENS::contains))
                .toList();
        assertTrue(offenders.isEmpty(),
                "a notification event that reports outward to an authority would make the product "
                        + "the one deciding to report, but found: " + offenders
                        + ". Reporting is a human decision (ADR-0051 §5); automate it and a false "
                        + "positive can never be taken back.");

        // Pinned as a set. All four are internal: two case signals and the two SLA timers the
        // acknowledgement net raises for staff (#3271). None addresses anyone outside the org,
        // which is the property under test — a new member must be checked against that, and a
        // named set makes the reviewer see exactly which one appeared.
        assertEquals(
                new java.util.TreeSet<>(Set.of(
                        "NEW_REPORT", "REPORTER_MESSAGE", "SLA_BREACH", "SLA_APPROACHING")),
                new java.util.TreeSet<>(events),
                "the notification vocabulary changed; ADR-0051 §4 bounds what the product may "
                        + "emit on its own — confirm the new event stays inside the organisation");
    }
}
