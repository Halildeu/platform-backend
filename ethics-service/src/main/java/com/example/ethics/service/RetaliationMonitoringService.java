package com.example.ethics.service;

import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.RetaliationCheckRepository;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * ES-213 (#3375) — keeps asking after the reporter once the case is over.
 *
 * <p>Directive 2019/1937 protects the reporter (art. 19 lists the prohibited forms of
 * retaliation, art. 21 the protection owed), and that duty outlives the investigation.
 * Retaliation is usually not the dismissal that follows the report by a week; it is the
 * transfer three months later, the appraisal at six, the contract not renewed at twelve.
 * A programme that stops at closure misses all three.
 *
 * <p>The schedule is Açık Holding's MDL35: three, six and twelve months. The checks are
 * opened by the system at closure rather than by whoever remembers, because a monitoring
 * duty discharged from memory is discharged for the cases people feel bad about and
 * forgotten for the rest.
 *
 * <h2>Anonymous reporters included</h2>
 *
 * <p>The check is asked through the mailbox the reporter already holds, so being protected
 * never requires giving a name. This matters more than it first appears: a scheme that
 * could only follow up with people who identified themselves would protect exactly the
 * people who felt safe enough not to need it, and abandon the ones who did.
 */
@Service
public class RetaliationMonitoringService {

    /** MDL35's periods. Not configurable: they are the schedule the policy commits to. */
    static final short[] PERIODS = {3, 6, 12};

    private final RetaliationCheckRepository checks;

    public RetaliationMonitoringService(RetaliationCheckRepository checks) {
        this.checks = checks;
    }

    /**
     * Opens the three checks for a case that has just concluded.
     *
     * <p>Idempotent by design rather than by luck: closing a case twice, or a retry after a
     * partial failure, must not produce six checks and make the backlog look worse than it
     * is. The unique constraint on (case_id, period_months) enforces the same rule one
     * layer down.
     *
     * @param closedAt the case's own closure time, not "now" — a case closed in a backfill
     *     or reconciled late still owes its reporter three, six and twelve months from the
     *     day it actually ended.
     */
    @Transactional
    public List<RetaliationCheck> openScheduleFor(UUID caseId, UUID orgId, Instant closedAt) {
        if (checks.existsByCaseId(caseId)) {
            return checks.findAllByCaseIdOrderByPeriodMonthsAsc(caseId);
        }
        for (short months : PERIODS) {
            Instant due = closedAt.atZone(ZoneOffset.UTC).plusMonths(months).toInstant();
            checks.save(new RetaliationCheck(UUID.randomUUID(), caseId, orgId, months, due));
        }
        return checks.findAllByCaseIdOrderByPeriodMonthsAsc(caseId);
    }

    /** What is due and still open. The one number that says whether this is being run. */
    @Transactional(readOnly = true)
    public List<RetaliationCheck> due(UUID orgId, Instant now) {
        return checks.findAllByOrgIdAndClosedAtIsNullAndDueAtLessThanEqual(orgId, now);
    }
}
