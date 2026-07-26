package com.example.ethics.notification;

import com.example.ethics.repository.NotificationOutboxRepository;
import com.example.ethics.security.StaffContext;
import java.time.Clock;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Operator recovery for notification signals that ran out of attempts.
 *
 * <p>Delivery can fail for reasons that have nothing to do with the signal: a
 * service running an image older than the client registration it needs, a
 * channel name spelled one character differently on each side, a recipient
 * grant that was never seeded. All three happened at once in the TEST cell, and
 * because `DEAD_LETTER` was terminal the signals lost to them could never be
 * sent again. The reports survived; the alert that a report existed did not.
 *
 * <p>Scoped to the caller's own tenant, bounded per call, and audited. It never
 * runs on a schedule: requeueing without knowing whether the cause is fixed
 * just walks the same rows back to the same limit, and leaves an operator
 * believing the backlog was handled.
 */
@Service
public class NotificationDeadLetterService {
    private static final Logger log =
            LoggerFactory.getLogger(NotificationDeadLetterService.class);
    private static final int MAX_BATCH = 500;

    private final NotificationOutboxRepository outbox;
    private final Clock clock;

    public NotificationDeadLetterService(
            NotificationOutboxRepository outbox, Clock clock) {
        this.outbox = outbox;
        this.clock = clock;
    }

    /** Count and age of the caller tenant's stranded signals. */
    @Transactional(readOnly = true)
    public DeadLetterSummary summary(StaffContext context) {
        Object[] row = outbox.deadLetterSummary(context.orgId());
        // A native projection of one row arrives wrapped when the query has a
        // single result; unwrap before reading.
        Object[] values = row.length == 1 && row[0] instanceof Object[] inner ? inner : row;
        long count = values[0] == null ? 0L : ((Number) values[0]).longValue();
        Instant oldest = values.length > 1 ? toInstant(values[1]) : null;
        Instant newest = values.length > 2 ? toInstant(values[2]) : null;
        return new DeadLetterSummary(count, oldest, newest);
    }

    /**
     * Return up to {@code limit} stranded signals to the queue.
     *
     * <p>Returns how many actually moved, which is not necessarily what was
     * asked for: another operator may have taken them first.
     */
    @Transactional
    public int requeue(StaffContext context, int limit) {
        int bounded = Math.max(1, Math.min(limit, MAX_BATCH));
        int moved = outbox.requeueDeadLetters(
                context.orgId(), clock.instant(), bounded);
        // Deliberately org + count only. This log line must stay safe to ship
        // to a collector: no case, report, receipt or reporter identity.
        log.warn("Etik Speak notification dead-letter requeue: org={} moved={} requested={}",
                context.orgId(), moved, bounded);
        return moved;
    }

    private static Instant toInstant(Object value) {
        if (value == null) return null;
        if (value instanceof Instant instant) return instant;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toInstant();
        if (value instanceof java.time.OffsetDateTime offset) return offset.toInstant();
        return null;
    }

    public record DeadLetterSummary(long count, Instant oldest, Instant newest) {}
}
