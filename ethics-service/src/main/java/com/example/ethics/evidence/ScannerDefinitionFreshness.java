package com.example.ethics.evidence;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * How old are the signatures the scanner is actually using? (#3354)
 *
 * <p>The scanner has no egress by design — it is the component that touches attacker-supplied
 * files, so it must not be able to reach out. The consequence is that its signatures cannot
 * update at runtime: they arrive with the pinned image and stay frozen until the image is
 * re-pinned. That is the correct trade-off, but it has a silent cost. A ClamAV running on
 * six-month-old definitions still answers {@code SelfCheck: Database status OK} every ten
 * minutes and still scans every upload. It simply catches less. Nothing in the health surface
 * distinguishes it from a current one.
 *
 * <p>ES-306 proved with EICAR that the scanner <em>works</em>. It did not prove its signatures
 * are <em>current</em> — two different questions, and this measures the second.
 *
 * <p>It polls rather than piggy-backing on scans. In a low-volume reporting channel weeks can
 * pass without a single attachment, and an age that only refreshed on traffic would go stale
 * precisely in the quiet system where staleness is most dangerous.
 */
@Component
@ConditionalOnBean(ClamAvScanner.class)
public class ScannerDefinitionFreshness {

    private static final Logger log = LoggerFactory.getLogger(ScannerDefinitionFreshness.class);

    /** {@code ClamAV 1.5.0/28077/Thu Jul 30 22:30:00 2026} — the third field is the build date. */
    private static final Pattern VERSION = Pattern.compile("^ClamAV [^/]+/(\\d+)/(.+)$");
    private static final DateTimeFormatter SIGNATURE_DATE =
            DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss yyyy", Locale.ENGLISH);

    private final ClamAvScanner scanner;
    private final Clock clock;
    private final AtomicReference<Instant> definitionBuiltAt = new AtomicReference<>();

    public ScannerDefinitionFreshness(ClamAvScanner scanner, MeterRegistry metrics) {
        this(scanner, metrics, Clock.systemUTC());
    }

    ScannerDefinitionFreshness(ClamAvScanner scanner, MeterRegistry metrics, Clock clock) {
        this.scanner = scanner;
        this.clock = clock;
        Gauge.builder("ethics.evidence.scanner.definition.age.seconds", this,
                        ScannerDefinitionFreshness::ageSeconds)
                .description("Age of the signature set the evidence scanner is currently running")
                .register(metrics);
    }

    /**
     * NaN until a probe succeeds, never 0. Zero would read as "brand new definitions", which is
     * the opposite of "we have not been able to ask" — and an alert on age would stay quiet.
     */
    double ageSeconds() {
        Instant builtAt = definitionBuiltAt.get();
        if (builtAt == null) {
            return Double.NaN;
        }
        return Math.max(0, Duration.between(builtAt, clock.instant()).toSeconds());
    }

    @Scheduled(
            initialDelayString = "${ethics.evidence.scanner.freshness-initial-delay:30s}",
            fixedDelayString = "${ethics.evidence.scanner.freshness-poll-delay:15m}")
    void probe() {
        try {
            parseBuildDate(scanner.currentVersion()).ifPresentOrElse(
                    definitionBuiltAt::set,
                    () -> log.warn("Scanner version string carried no parseable signature date"));
        } catch (RuntimeException unavailable) {
            // Deliberately keeps the last known value rather than clearing it: a scanner that is
            // briefly unreachable has not suddenly acquired fresh definitions, and blanking the
            // gauge would silence the very alert this exists to raise.
            log.warn("Scanner definition freshness probe failed: {}", unavailable.getClass().getSimpleName());
        }
    }

    static Optional<Instant> parseBuildDate(String version) {
        if (version == null) {
            return Optional.empty();
        }
        var matcher = VERSION.matcher(version.trim());
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            LocalDateTime local = LocalDateTime.parse(matcher.group(2).trim(), SIGNATURE_DATE);
            return Optional.of(local.toInstant(ZoneOffset.UTC));
        } catch (RuntimeException unparseable) {
            return Optional.empty();
        }
    }
}
