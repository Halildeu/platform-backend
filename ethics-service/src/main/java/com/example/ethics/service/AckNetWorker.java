package com.example.ethics.service;

import com.example.ethics.model.AckTemplate;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.EthicsReport;
import com.example.ethics.repository.AckTemplateRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.EthicsReportRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * ES-2 (#3271) — the last-day net under the seven-day acknowledgement promise.
 *
 * <p>The statutory clock (art. 9(1)(b)) must not hang on a person's memory. When the
 * seventh day begins and nobody has written to the reporter, this worker sends the
 * resolved template AS-IS and stamps the ledger {@code dispatch=AUTOMATIC} — the
 * exception made visible, never the norm made invisible. Six days, not seven: the net
 * fires at the START of the last day so the send is inside the window with a full day
 * of margin, not a race against midnight.
 *
 * <p>Idempotent by construction: the message idempotency key is derived from the case,
 * and {@code markAcknowledged}'s row-count guard means a concurrent human send wins —
 * this worker's message then arrives as an ordinary staff reply, never a second
 * acknowledgement claim.
 *
 * <p>Runs only where {@code ethics.acknowledgement.net-enabled} is explicitly true —
 * the request-facing deployment, alongside the delivery loops, for the same reason:
 * one poller per outbox-shaped job.
 */
@Component
public class AckNetWorker {
    private static final Logger log = LoggerFactory.getLogger(AckNetWorker.class);
    /** The net fires when the sixth full day has elapsed — the seventh day's start. */
    private static final Duration NET_AGE = Duration.ofDays(6);

    private final EthicsCaseRepository cases;
    private final EthicsReportRepository reports;
    private final AckTemplateRepository templates;
    private final AcknowledgementService acknowledgements;
    private final EthicsService ethics;
    private final boolean enabled;
    private final Counter dispatched;
    private final Counter failed;

    public AckNetWorker(
            EthicsCaseRepository cases,
            EthicsReportRepository reports,
            AckTemplateRepository templates,
            AcknowledgementService acknowledgements,
            EthicsService ethics,
            @Value("${ethics.acknowledgement.net-enabled:false}") boolean enabled,
            MeterRegistry metrics) {
        this.cases = cases;
        this.reports = reports;
        this.templates = templates;
        this.acknowledgements = acknowledgements;
        this.ethics = ethics;
        this.enabled = enabled;
        this.dispatched = Counter.builder("ethics.acknowledgement.net.dispatched")
                .description("Acknowledgements sent by the last-day net (dispatch=AUTOMATIC)")
                .register(metrics);
        this.failed = Counter.builder("ethics.acknowledgement.net.failed")
                .description("Last-day net attempts that failed and will retry next cycle")
                .register(metrics);
    }

    @Scheduled(fixedDelayString = "${ethics.acknowledgement.net-poll-delay:15m}")
    void scheduledCycle() {
        if (enabled) runCycle(Instant.now());
    }

    public int runCycle(Instant now) {
        List<UUID> due = cases.findUnacknowledgedOpenBefore(now.minus(NET_AGE));
        int sent = 0;
        for (UUID caseId : due) {
            try {
                sent += dispatchNet(caseId, now) ? 1 : 0;
            } catch (RuntimeException error) {
                // One stuck case must not stall the net for the rest. No identifiers in
                // the log line — the same discipline as every other worker here.
                failed.increment();
                log.warn("Etik Speak ack net: a case dispatch failed; will retry next cycle", error);
            }
        }
        if (!due.isEmpty()) {
            log.info("Etik Speak ack net cycle candidates={} dispatched={}", due.size(), sent);
        }
        return sent;
    }

    private boolean dispatchNet(UUID caseId, Instant now) {
        EthicsCase item = cases.findById(caseId).orElse(null);
        if (item == null || item.getAcknowledgedAt() != null) return false;
        EthicsReport report = reports.findByCaseId(caseId).orElse(null);
        if (report == null) return false;
        AckTemplate template = templates.resolve(item.getOrgId(), report.getCategory()).orElse(null);
        if (template == null) {
            // No template is a configuration wound, not a reason to stay silent forever;
            // it fails loudly in metrics/logs and retries once someone seeds one.
            throw new IllegalStateException("ACK_TEMPLATE_MISSING");
        }
        String body = acknowledgements.fill(template.getBody(), item);
        // One deterministic key per case: a rerun after a crash replays idempotently.
        // The dispatch record rides the acknowledgement stamp itself — only the call
        // that actually stamped writes it, so a concurrent second poller (the 2026-08-01
        // live race) can no longer produce a duplicate ledger entry.
        boolean[] stamped = {false};
        ethics.systemReply(item.getOrgId(), caseId, "ack-net-" + caseId, body, () -> {
            acknowledgements.appendDispatchAudit(
                    item.getOrgId(), caseId, template.getId(), template.getVersion(),
                    "AUTOMATIC", List.of());
            stamped[0] = true;
        });
        if (stamped[0]) dispatched.increment();
        return stamped[0];
    }
}
