package com.example.ethics.evidence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ScannerDefinitionFreshnessTest {

    /** Verbatim from the live TEST cell (`clamdscan -V`, 2026-08-02) — not a shape I invented. */
    private static final String LIVE = "ClamAV 1.5.3/28077/Thu Jul 30 06:24:42 2026";

    private static Clock at(String iso) {
        return Clock.fixed(Instant.parse(iso), ZoneOffset.UTC);
    }

    @Test
    @DisplayName("the signature build date is read out of the scanner's own version string")
    void parsesTheLiveVersionShape() {
        var built = ScannerDefinitionFreshness.parseBuildDate(LIVE);
        assertTrue(built.isPresent(), "the live version shape must parse");
        assertEquals(Instant.parse("2026-07-30T06:24:42Z"), built.get());
    }

    @Test
    @DisplayName("single-digit days parse too — they are space-padded and would break a naive format")
    void handlesSpacePaddedDayOfMonth() {
        var built = ScannerDefinitionFreshness.parseBuildDate("ClamAV 1.5.3/28080/Sun Aug  9 06:24:42 2026");
        assertTrue(built.isPresent(),
                "ClamAV pads days 1-9 with a space; failing here would blind the metric for a "
                        + "third of every month");
        assertEquals(Instant.parse("2026-08-09T06:24:42Z"), built.get());
    }

    @Test
    @DisplayName("a version string that does not carry a date yields nothing, not a wrong date")
    void refusesToGuessWhenTheShapeIsUnfamiliar() {
        assertFalse(ScannerDefinitionFreshness.parseBuildDate(null).isPresent());
        assertFalse(ScannerDefinitionFreshness.parseBuildDate("").isPresent());
        assertFalse(ScannerDefinitionFreshness.parseBuildDate("ClamAV 1.5.0").isPresent());
        assertFalse(ScannerDefinitionFreshness.parseBuildDate("something else entirely").isPresent());
        // A future ClamAV could change the format. Returning an unparseable date as "now" would
        // report perfectly fresh definitions forever, which is the worst possible failure here.
        assertFalse(ScannerDefinitionFreshness.parseBuildDate("ClamAV 9.9.9/1/not a date").isPresent());
    }

    @Test
    @DisplayName("age is unknown (NaN) before the first successful probe, never zero")
    void unknownIsNotFresh() {
        var scanner = mock(ClamAvScanner.class);
        var freshness = new ScannerDefinitionFreshness(
                scanner, new SimpleMeterRegistry(), at("2026-08-02T00:00:00Z"));
        assertTrue(Double.isNaN(freshness.ageSeconds()),
                "an un-probed scanner must report unknown, not 0 — zero reads as brand-new "
                        + "definitions and would keep a staleness alert silent");
    }

    @Test
    @DisplayName("age grows from the signature build date")
    void ageIsMeasuredFromTheBuildDate() {
        var scanner = mock(ClamAvScanner.class);
        when(scanner.currentVersion()).thenReturn(LIVE);
        var freshness = new ScannerDefinitionFreshness(
                scanner, new SimpleMeterRegistry(), at("2026-08-02T06:24:42Z"));
        freshness.probe();
        assertEquals(Duration.ofDays(3).toSeconds(), (long) freshness.ageSeconds());
    }

    @Test
    @DisplayName("an unreachable scanner keeps the last known age instead of blanking it")
    void anUnreachableScannerDoesNotSilenceTheAlert() {
        var scanner = mock(ClamAvScanner.class);
        when(scanner.currentVersion()).thenReturn(LIVE);
        var freshness = new ScannerDefinitionFreshness(
                scanner, new SimpleMeterRegistry(), at("2026-08-02T06:24:42Z"));
        freshness.probe();
        double known = freshness.ageSeconds();

        when(scanner.currentVersion()).thenThrow(new IllegalStateException("connection refused"));
        freshness.probe();

        assertEquals(known, freshness.ageSeconds(),
                "a scanner that is briefly unreachable has not acquired fresh definitions; "
                        + "clearing the gauge would silence the alert this metric exists to raise");
    }

    @Test
    @DisplayName("the gauge is registered under the ethics_evidence_* family")
    void gaugeIsRegistered() {
        var registry = new SimpleMeterRegistry();
        new ScannerDefinitionFreshness(mock(ClamAvScanner.class), registry, at("2026-08-02T00:00:00Z"));
        assertTrue(
                registry.getMeters().stream().anyMatch(meter ->
                        meter.getId().getName().equals("ethics.evidence.scanner.definition.age.seconds")),
                "the metric must exist so a PrometheusRule can alert on it");
    }
}
