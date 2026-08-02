package com.example.ethics.service;

import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.RetaliationCheckRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ES-213 (#3375) — publishes how far behind the retaliation-monitoring programme is.
 *
 * <p>The number that matters is not how many checks exist; it is how many fell due and
 * were never asked. A programme with a full schedule and nothing ever asked looks, from
 * the case list, exactly like one running perfectly — every case closed, every check
 * created. The overdue count is the only place the difference shows.
 *
 * <p>Reported per org and per period, because those fail differently. Three-month checks
 * slipping is a staffing problem; twelve-month checks slipping is a programme that quietly
 * stopped believing the risk lasts that long — and art. 19's list is full of forms that
 * only appear at twelve months, like a contract not renewed.
 */
@Component
public class RetaliationDueSweeper {

    private final RetaliationCheckRepository checks;
    private final MultiGauge overdue;
    private final MultiGauge unasked;

    // One constructor, and it asks only for beans this application actually defines. An
    // injected Clock reads as the tidier choice until the context fails to start over a
    // bean nobody publishes — and the failure surfaces in every @SpringBootTest at once,
    // far from the class that caused it. Time enters through sweepAt instead, which also
    // makes the schedule testable without a bean at all.
    public RetaliationDueSweeper(RetaliationCheckRepository checks, MeterRegistry metrics) {
        this.checks = checks;
        this.overdue = MultiGauge.builder("ethics_retaliation_checks_overdue")
                .description("Retaliation checks past due and not concluded, by org and period")
                .register(metrics);
        this.unasked = MultiGauge.builder("ethics_retaliation_checks_due_never_asked")
                .description("Retaliation checks past due that were never put to the reporter")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${ethics.retaliation.sweep-delay:1h}")
    public void sweep() {
        sweepAt(Instant.now());
    }

    void sweepAt(Instant now) {
        List<RetaliationCheck> due = checks.findAll().stream()
                .filter(c -> c.getClosedAt() == null && !c.getDueAt().isAfter(now))
                .toList();

        publish(overdue, due.stream());
        // Deliberately separate from the count above. A check that is late because the
        // reporter has not answered is a different failure from one nobody has asked, and
        // only the second is the organisation's fault. Reporting them as one number lets
        // the second hide inside the first.
        publish(unasked, due.stream().filter(c -> c.getAskedAt() == null));
    }

    private void publish(MultiGauge gauge, java.util.stream.Stream<RetaliationCheck> rows) {
        Map<Key, Long> counts = rows.collect(Collectors.groupingBy(
                c -> new Key(c.getOrgId(), c.getPeriodMonths()), Collectors.counting()));
        gauge.register(counts.entrySet().stream()
                .map(e -> MultiGauge.Row.of(
                        Tags.of("org", e.getKey().orgId().toString(),
                                "period_months", String.valueOf(e.getKey().periodMonths())),
                        e.getValue()))
                .toList(), true);
    }

    /** How long the oldest outstanding check has been waiting — the age of the neglect. */
    public Duration oldestOutstanding(Instant now) {
        return checks.findAll().stream()
                .filter(c -> c.getClosedAt() == null && !c.getDueAt().isAfter(now))
                .map(c -> Duration.between(c.getDueAt(), now))
                .max(Duration::compareTo)
                .orElse(Duration.ZERO);
    }

    private record Key(UUID orgId, short periodMonths) {}
}
