package com.example.ethics.service;

import com.example.ethics.notification.NotificationOutboxPublisher;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.NotificationOutboxRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * ES-301 — tell the organisation when it has missed a legal deadline.
 *
 * <p>The two existing notification events both fire when a <em>reporter</em> acts: a report
 * arrived, a message arrived. Nothing fired when the organisation failed to act, which is
 * the case that actually needed saying. Measured on the live cell: fifty-one acknowledgements
 * past the seven-day mark under EU 2019/1937 art. 9(1)(b), and no message anywhere.
 *
 * <p><strong>One signal per organisation per day, not one per case.</strong> Fifty-one
 * breaches would otherwise become fifty-one notifications, and a channel that floods on the
 * first bad day is a channel people mute — after which the fifty-second breach is no louder
 * than silence. The signal says "you have overdue obligations"; which ones is a question for
 * the staff list, which orders by how far past the deadline each case is.
 *
 * <p>Carries no case id, matching the outbox's existing contract: the transport stays free of
 * case-level facts even though this sweeper knows them.
 *
 * <p><strong>Every tenant, not the configured one.</strong> The first version swept the single
 * organisation named by {@code ethics.public-org-id}, which on the live cell covered 139 cases
 * and missed 28 belonging to a second tenant. Those cases carry the same legal deadlines; a
 * sweep that reads a config value would let a tenant whose host entry is missing or stale drop
 * out silently. The organisation list therefore comes from the cases themselves.
 */
@Component
public class SlaBreachSweeper {

    private static final Logger log = LoggerFactory.getLogger(SlaBreachSweeper.class);

    /** How long a signal suppresses the next one for the same organisation. */
    private static final Duration ONE_PER = Duration.ofDays(1);

    private final EthicsCaseRepository cases;
    private final NotificationOutboxRepository outbox;
    private final NotificationOutboxPublisher notifications;
    private final CaseSlaClock sla;
    private final BusinessCalendar calendar;
    private final com.example.ethics.config.EthicsSlaCalendarProperties calendarConfig;
    private final Clock clock;

    // Two constructors, so Spring must be told which one. Without this it looks for a
    // default constructor, fails to find one, and the whole application context dies —
    // taking every integration test with it. The unit test below cannot catch that: it
    // builds the sweeper by hand and never asks Spring to wire it.
    @Autowired
    public SlaBreachSweeper(
            EthicsCaseRepository cases,
            NotificationOutboxRepository outbox,
            NotificationOutboxPublisher notifications,
            CaseSlaClock sla,
            BusinessCalendar calendar,
            com.example.ethics.config.EthicsSlaCalendarProperties calendarConfig) {
        this(cases, outbox, notifications, sla, calendar, calendarConfig, Clock.systemUTC());
    }

    SlaBreachSweeper(
            EthicsCaseRepository cases,
            NotificationOutboxRepository outbox,
            NotificationOutboxPublisher notifications,
            CaseSlaClock sla,
            BusinessCalendar calendar,
            com.example.ethics.config.EthicsSlaCalendarProperties calendarConfig,
            Clock clock) {
        this.cases = cases;
        this.outbox = outbox;
        this.notifications = notifications;
        this.sla = sla;
        this.calendar = calendar;
        this.calendarConfig = calendarConfig;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${ethics.sla.sweep-delay:15m}")
    @Transactional
    public void sweep() {
        Instant now = clock.instant();
        for (UUID orgId : cases.findDistinctOrgIds()) {
            // Suppression is per organisation: one tenant's quiet day must not silence
            // another tenant's breach.
            if (outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(
                    orgId, NotificationOutboxPublisher.SLA_BREACH, now.minus(ONE_PER))) {
                continue;
            }
            if (hasBreach(orgId)) {
                notifications.enqueue(orgId, NotificationOutboxPublisher.SLA_BREACH, now);
                // Deliberately no count and no case id: the log line is operational, and a breach
                // count is a fact about live whistleblowing cases. The organisation is not named
                // either — which tenant is behind is the same class of fact.
                log.info("Etik Speak: SLA breach signal enqueued for one organisation");
                // A breach subsumes a warning. "You have missed a deadline" and "a deadline is
                // near" on the same day are one piece of news, not two.
                continue;
            }
            if (!calendarConfig.warningEnabled()) {
                continue;
            }
            if (outbox.existsByOrgIdAndEventTypeAndCreatedAtAfter(
                    orgId, NotificationOutboxPublisher.SLA_APPROACHING, now.minus(ONE_PER))) {
                continue;
            }
            if (hasApproachingDeadline(orgId, now)) {
                notifications.enqueue(orgId, NotificationOutboxPublisher.SLA_APPROACHING, now);
                log.info("Etik Speak: SLA approaching signal enqueued for one organisation");
            }
        }
    }

    /**
     * Stops at the first breach. The signal is the same whether one obligation is overdue or
     * fifty, so counting them would cost a full scan to produce a number nothing reads.
     */
    /**
     * A pending obligation whose deadline is at most the configured number of working days
     * away. The deadline itself stays calendar time — EU 2019/1937 counts calendar days and
     * {@code CaseSlaClock} has no parameter through which this calendar could reach it. What
     * the working-day count changes is only <em>when the organisation is told</em>: a Monday
     * deadline is more urgent on Friday than the calendar distance suggests, because the days
     * between are days nobody is at work.
     */
    private boolean hasApproachingDeadline(UUID orgId, Instant now) {
        int window = calendarConfig.warnBusinessDays();
        for (var item : cases.findAllByOrgIdOrderByUpdatedAtDesc(orgId)) {
            var ack = sla.acknowledgement(item.getCreatedAt(), item.getAcknowledgedAt());
            if (ack.state() == CaseSlaClock.AcknowledgementState.PENDING
                    && calendar.businessDaysUntil(now, ack.dueAt()) <= window) {
                return true;
            }
            var feedback = sla.feedback(item.getCreatedAt(), item.getClosedAt());
            if (feedback.state() == CaseSlaClock.FeedbackState.PENDING
                    && calendar.businessDaysUntil(now, feedback.dueAt()) <= window) {
                return true;
            }
        }
        return false;
    }

    private boolean hasBreach(UUID orgId) {
        for (var item : cases.findAllByOrgIdOrderByUpdatedAtDesc(orgId)) {
            var ack = sla.acknowledgement(item.getCreatedAt(), item.getAcknowledgedAt());
            if (ack.state() == CaseSlaClock.AcknowledgementState.BREACHED) return true;
            var feedback = sla.feedback(item.getCreatedAt(), item.getClosedAt());
            if (feedback.state() == CaseSlaClock.FeedbackState.BREACHED) return true;
        }
        return false;
    }
}
