package com.example.ethics.service;

import com.example.ethics.audit.EthicsAuditChain;
import com.example.ethics.config.EthicsSlaEscalationProperties;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.model.CaseEscalation;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.CaseEscalationRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ES-301 — turns a missed deadline into a recorded escalation (#882).
 *
 * <p>{@link SlaBreachSweeper} tells the organisation, once a day, that it is behind. This
 * class writes down, per case and per obligation, that on this instant the matter reached
 * level N under the organisation's escalation policy — a row nothing can edit and an audit
 * event on the WORM ledger. The two answer different questions: "are we behind?" and, years
 * later, "when did we know, and how far behind were we when we did?".
 *
 * <p><strong>Deterministic on {@code now}.</strong> Every decision here is arithmetic on the
 * case's own timestamps, the configured windows and the instant passed in. The clock is used
 * only to supply that instant on the schedule; a test passes its own. A deadline is missed
 * when {@code now} is strictly after it — at the exact instant nothing is late yet. The
 * instant is truncated to microseconds first, because that is what PostgreSQL stores: a
 * {@code now} one nanosecond past a threshold would be "after" in Java and "equal" in the
 * row's CHECK constraint, and the transaction would fail on a difference nothing can see.
 *
 * <p><strong>A pause changes nothing here</strong>, for the reason {@link CaseSlaClock} gives:
 * the deadline does not move, so neither does the level reached against it. Meeting the
 * obligation stops further levels and erases none.
 *
 * <p><strong>One transaction per case, on the case row, without waiting.</strong> The sweeper
 * runs in every replica with no distributed lock. Each case is escalated inside its own
 * {@code REQUIRES_NEW} transaction that first takes the case row with {@code NOWAIT}, so it
 * decides "still unacknowledged / still open" against the same row the acknowledgement and
 * closure writes update. A row someone else holds — an acknowledgement in flight, another
 * replica on the same case — is <em>deferred</em> to the next cycle rather than waited for:
 * a wait with no bound would let one held row stall every case behind it in the candidate
 * list, and the next cycle sees whatever the holder committed. The unique index on
 * {@code (case, obligation, level)} is the backstop for whatever the lock does not cover: a
 * collision on that index is another replica having already written the row, and is skipped
 * <em>after</em> the transaction has rolled back — never swallowed inside it, where the
 * connection is already aborted. Any other integrity failure is a fault and is counted as one.
 *
 * <p><strong>Records the present, not the past.</strong> If the sweeper is down while a
 * threshold passes and the obligation is met before it returns, no row is written for the
 * interval: the level was never <em>recorded</em>, and inventing a timestamp afterwards would
 * be a claim about when the organisation knew that nothing supports. The audit outbox row is
 * written in the same transaction as the escalation, so an audit-delivery outage delays the
 * ledger entry and loses nothing.
 *
 * <p><strong>The log carries counts and a failure class, never an exception.</strong> A
 * database error's text can quote the failing row — case id, organisation, timestamps — and
 * a stack trace carries that text into every log sink. Which tenant is behind on a
 * whistleblowing obligation is a fact about that tenant. {@code EscalationSweeperTest} pins
 * this by capturing the log on every failure path.
 */
@Component
public class EscalationSweeper {

    private static final Logger log = LoggerFactory.getLogger(EscalationSweeper.class);

    /** Audit event type. Rendered on the case timeline as "OBLIGATION L&lt;n&gt;". */
    public static final String EVENT_TYPE = "ethics.case.escalated";

    /** The unique index a losing replica trips over — V25. */
    public static final String LEVEL_INDEX = "ux_ethics_escalation_level";

    private final EthicsCaseRepository cases;
    private final CaseEscalationRepository escalations;
    private final AuditOutboxRepository audit;
    private final CaseSlaClock sla;
    private final EthicsSlaEscalationProperties policy;
    private final TransactionOperations perCase;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Counter recorded;
    private final Counter deferred;
    private final Counter failed;

    // Two constructors, so Spring must be told which one (see SlaBreachSweeper for the
    // context death that omitting this caused).
    @Autowired
    public EscalationSweeper(
            EthicsCaseRepository cases,
            CaseEscalationRepository escalations,
            AuditOutboxRepository audit,
            CaseSlaClock sla,
            EthicsSlaEscalationProperties policy,
            PlatformTransactionManager transactions,
            ObjectMapper mapper,
            MeterRegistry metrics) {
        this(cases, escalations, audit, sla, policy, requiresNew(transactions), mapper, metrics,
                Clock.systemUTC());
    }

    EscalationSweeper(
            EthicsCaseRepository cases,
            CaseEscalationRepository escalations,
            AuditOutboxRepository audit,
            CaseSlaClock sla,
            EthicsSlaEscalationProperties policy,
            TransactionOperations perCase,
            ObjectMapper mapper,
            MeterRegistry metrics,
            Clock clock) {
        this.cases = cases;
        this.escalations = escalations;
        this.audit = audit;
        this.sla = sla;
        this.policy = policy;
        this.perCase = perCase;
        this.mapper = mapper;
        this.clock = clock;
        this.recorded = Counter.builder("ethics.sla.escalation.recorded")
                .description("Escalation levels committed (one per case, obligation and level)")
                .register(metrics);
        this.deferred = Counter.builder("ethics.sla.escalation.deferred")
                .description("Cases whose row was held by another transaction; retried next cycle")
                .register(metrics);
        this.failed = Counter.builder("ethics.sla.escalation.failed")
                .description("Cases whose escalation transaction failed and will be retried next cycle")
                .register(metrics);
    }

    private static TransactionOperations requiresNew(PlatformTransactionManager transactions) {
        var template = new TransactionTemplate(transactions);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    /**
     * @param skipped cases another replica had already escalated when this one got there
     * @param deferred cases whose row was held by another transaction; retried next cycle
     */
    public record CycleResult(boolean enabled, int candidates, int recorded, int skipped,
            int deferred, int failed) {
        static final CycleResult DISABLED = new CycleResult(false, 0, 0, 0, 0, 0);
    }

    // An initial delay, not an immediate first run: with none, the first cycle fired the
    // instant the context was ready — in production before the pod was warm, and in the
    // integration tests on the same database the test was driving with its own fixed clock,
    // where it recorded levels at wall-clock time and held row locks the test then found
    // taken (CI flake on the microsecond boundary test, 2026-09-10). The tests pin the delay
    // to 30 days; production waits a minute.
    @Scheduled(initialDelayString = "${ethics.sla.escalation.initial-delay:PT1M}",
               fixedDelayString = "${ethics.sla.escalation.poll-delay:15m}")
    void scheduledCycle() {
        runCycle(clock.instant());
    }

    /**
     * One pass over every case that still owes something. Disabled means disabled here too,
     * not only on the schedule: a caller cannot escalate through the public method what the
     * owner has not switched on.
     */
    public CycleResult runCycle(Instant at) {
        if (!policy.enabled()) return CycleResult.DISABLED;
        // Microseconds, like the column. See the class comment.
        Instant now = EthicsAuditChain.normalizeTimestamp(at);
        List<UUID> candidates = cases.findWithUnmetObligations();
        int written = 0;
        int skipped = 0;
        int held = 0;
        int faults = 0;
        for (UUID caseId : candidates) {
            try {
                Integer count = perCase.execute(status -> escalate(caseId, now));
                // Counted only once the transaction has returned: a level rolled back with
                // its transaction was never recorded, and the meter must not say it was.
                int committed = count == null ? 0 : count;
                written += committed;
                recorded.increment(committed);
            } catch (PessimisticLockingFailureException heldByAnother) {
                // NOWAIT: an acknowledgement or closure in flight, or another replica on
                // this case. Next cycle reads whatever they committed.
                held++;
                deferred.increment();
            } catch (DataIntegrityViolationException collision) {
                // Reached only after the per-case transaction has rolled back.
                if (isLevelCollision(collision)) {
                    skipped++;
                } else {
                    faults++;
                    failed.increment();
                    log.warn("Etik Speak escalation: a case could not be escalated (kind={}); will retry next cycle",
                            kind(collision));
                }
            } catch (RuntimeException error) {
                // One stuck case must not stall the rest. The class name is the whole
                // diagnostic: the message and the trace can quote the failing row.
                faults++;
                failed.increment();
                log.warn("Etik Speak escalation: a case could not be escalated (kind={}); will retry next cycle",
                        kind(error));
            }
        }
        if (written > 0 || faults > 0 || held > 0) {
            log.info("Etik Speak escalation cycle candidates={} recorded={} skipped={} deferred={} failed={}",
                    candidates.size(), written, skipped, held, faults);
        }
        return new CycleResult(true, candidates.size(), written, skipped, held, faults);
    }

    /** Inside the per-case transaction, under the row lock. Returns how many levels were written. */
    private int escalate(UUID caseId, Instant now) {
        EthicsCase item = cases.lockById(caseId).orElse(null);
        if (item == null) return 0;
        int written = 0;
        if (item.getAcknowledgedAt() == null) {
            // Only the deadline is taken from the clock class; the state it computes reads
            // its own clock and would make this pass depend on wall time.
            Instant dueAt = sla.acknowledgement(item.getCreatedAt(), null).dueAt();
            written += record(item, CaseEscalation.ACKNOWLEDGEMENT, dueAt, now);
        }
        if (item.getClosedAt() == null) {
            Instant dueAt = sla.feedback(item.getCreatedAt(), null).dueAt();
            written += record(item, CaseEscalation.FEEDBACK, dueAt, now);
        }
        return written;
    }

    private int record(EthicsCase item, String obligation, Instant rawDueAt, Instant now) {
        if (rawDueAt == null) return 0;
        // One precision for everything compared, written and reported: microseconds. The
        // deadline inherits nanoseconds from a configured window, the threshold from a
        // configured step; either would let Java call a level reached that the row's CHECK
        // constraint, seeing the rounded values, refuses.
        Instant dueAt = EthicsSlaEscalationProperties.micro(rawDueAt);
        int target = policy.levelReached(dueAt, now);
        int written = 0;
        for (int level = 1; level <= target; level++) {
            if (escalations.existsByCaseIdAndObligationAndLevel(item.getId(), obligation, level)) continue;
            Instant thresholdAt = policy.thresholdAt(dueAt, level);
            escalations.save(new CaseEscalation(UUID.randomUUID(), item.getId(), item.getOrgId(),
                    obligation, level, dueAt, thresholdAt, now));
            audit.save(new AuditOutbox(UUID.randomUUID(), item.getOrgId(), item.getId(), EVENT_TYPE,
                    payload(obligation, level, dueAt, thresholdAt, now), now));
            written++;
        }
        return written;
    }

    /**
     * Exactly these six fields. No actor — the clock acted; no free text — free text collects
     * names; no organisation — the outbox row already carries it. {@code EscalationSweeperTest}
     * pins the key set.
     */
    private String payload(String obligation, int level, Instant dueAt, Instant thresholdAt, Instant now) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("obligation", obligation);
        fields.put("level", level);
        fields.put("dueAt", dueAt.toString());
        fields.put("thresholdAt", thresholdAt.toString());
        fields.put("escalatedAt", now.toString());
        fields.put("overdueSeconds", Duration.between(dueAt, now).getSeconds());
        try {
            return mapper.writeValueAsString(fields);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Escalation audit payload could not be serialised.", error);
        }
    }

    /** Only the level index counts as "someone else was first"; every other violation is a fault. */
    static boolean isLevelCollision(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.toLowerCase(Locale.ROOT).contains(LEVEL_INDEX)) return true;
            if (cause.getCause() == cause) break;
        }
        return false;
    }

    /** The failure's class, which is a closed vocabulary; never its message. */
    static String kind(Throwable error) {
        return error.getClass().getSimpleName();
    }
}
