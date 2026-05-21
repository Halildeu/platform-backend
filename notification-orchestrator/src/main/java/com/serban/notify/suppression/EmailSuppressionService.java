package com.serban.notify.suppression;

import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.repository.EmailSuppressionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Email suppression service (Faz 23.8 M7 T4.3.b — Codex `019e492f`
 * AGREE partial MVP).
 *
 * <p>Three responsibilities:
 * <ol>
 *   <li><b>Lookup</b>: {@link #isCurrentlyActive} called by
 *       {@code DeliveryEligibilityService} on every email dispatch.</li>
 *   <li><b>Upsert</b>: {@link #upsert} called by the future DSN poll
 *       worker, provider webhook adapter, or admin API to add/update
 *       a suppression row.</li>
 *   <li><b>Release</b>: {@link #release} called by admin API to
 *       manually un-suppress (e.g. user contacted support, alias change).</li>
 * </ol>
 *
 * <p>Soft-bounce threshold logic: a {@code SOFT_BOUNCE} event arrives
 * via {@link #upsert} with reason=SOFT_BOUNCE. Service tracks
 * {@code bounce_count} + {@code soft_window_started_at}; on
 * {@code bounce_count >= softBounceThreshold} within
 * {@code softBounceWindowDays} days, the row is escalated to
 * {@code SOFT_BOUNCE_REPEATED} with {@code suppressed_until = now +
 * softHoldDays}.
 */
@Service
public class EmailSuppressionService {

    private static final Logger log =
        LoggerFactory.getLogger(EmailSuppressionService.class);

    /** Threshold: 3 soft bounces within window → escalate. */
    public static final int SOFT_BOUNCE_THRESHOLD = 3;

    /** Window: 14-day rolling window for soft bounce count. */
    public static final int SOFT_BOUNCE_WINDOW_DAYS = 14;

    /** Hold: SOFT_BOUNCE_REPEATED suppressed for 7 days; release after. */
    public static final int SOFT_HOLD_DAYS = 7;

    private final EmailSuppressionRepository repository;
    private Clock clock = Clock.systemUTC();  // injectable for deterministic tests

    public EmailSuppressionService(EmailSuppressionRepository repository) {
        this.repository = repository;
    }

    /** Test hook — never set in production. */
    void setClock(Clock clock) {
        this.clock = clock;
    }

    /**
     * Returns the currently-active suppression entry for the given
     * recipient, if any. "Currently active" means:
     * <ul>
     *   <li>{@code suppressed_until = NULL} (permanent: HARD_BOUNCE,
     *       SPAM_COMPLAINT, or permanent MANUAL), OR</li>
     *   <li>{@code suppressed_until > now} (within soft hold window)</li>
     * </ul>
     *
     * <p>Returns empty optional for non-matching, expired soft holds,
     * or matching rows with future-effective {@code suppressed_until}.
     *
     * <p>Read-only; callable from a non-transactional context.
     */
    @Transactional(readOnly = true)
    public Optional<EmailSuppression> isCurrentlyActive(String orgId, String recipientHash) {
        return repository.findByOrgIdAndRecipientHash(orgId, recipientHash)
            .filter(row -> row.isCurrentlyActive(OffsetDateTime.now(clock)));
    }

    /**
     * Upsert a suppression event. The service applies the soft-bounce
     * threshold transition automatically.
     *
     * @param input event payload (org_id, recipient_hash, reason, source, ...)
     * @return the resulting row after upsert / transition
     */
    @Transactional
    public EmailSuppression upsert(UpsertInput input) {
        if (input.orgId == null || input.orgId.isBlank()) {
            throw new IllegalArgumentException("orgId is required");
        }
        if (input.recipientHash == null || input.recipientHash.isBlank()) {
            throw new IllegalArgumentException("recipientHash is required");
        }
        if (input.reason == null) {
            throw new IllegalArgumentException("reason is required");
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<EmailSuppression> existingOpt =
            repository.findByOrgIdAndRecipientHash(input.orgId, input.recipientHash);

        EmailSuppression row;
        if (existingOpt.isPresent()) {
            row = existingOpt.get();
            row.setLastSeenAt(now);
            row.setBounceCount(row.getBounceCount() + 1);
        } else {
            row = new EmailSuppression();
            row.setOrgId(input.orgId);
            row.setChannel("email");
            row.setRecipientHash(input.recipientHash);
            row.setRecipientType(input.recipientType != null
                ? input.recipientType
                : EmailSuppression.RecipientType.EXTERNAL);
            row.setFirstSeenAt(now);
            row.setLastSeenAt(now);
            row.setBounceCount(1);
            row.setCreatedAt(now);
            row.setCreatedBy(input.actor);
        }

        // Update audit + provenance fields from the event.
        row.setLastBounceSummaryRedacted(input.summaryRedacted);
        row.setLastSource(input.source);
        row.setLastProvider(input.provider);
        row.setLastProviderMsgId(input.providerMsgId);
        row.setLastEventFingerprint(input.eventFingerprint);
        row.setUpdatedAt(now);
        row.setUpdatedBy(input.actor);

        // Reason transition rules:
        switch (input.reason) {
            case HARD_BOUNCE:
            case SPAM_COMPLAINT:
            case MANUAL:
                // Permanent suppression; clear soft window state.
                row.setReason(input.reason);
                row.setSuppressedUntil(null);
                row.setSoftWindowStartedAt(null);
                break;
            case SOFT_BOUNCE_REPEATED:
                // Caller pre-classified; trust and persist.
                row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
                row.setSuppressedUntil(now.plusDays(SOFT_HOLD_DAYS));
                break;
        }

        EmailSuppression saved = repository.save(row);
        log.info(
            "email suppression upsert org={} reason={} count={} suppressed_until={}",
            saved.getOrgId(), saved.getReason(),
            saved.getBounceCount(), saved.getSuppressedUntil()
        );
        return saved;
    }

    /**
     * Handle a soft-bounce event with threshold tracking.
     *
     * @return the suppression row if escalated to SOFT_BOUNCE_REPEATED,
     *         empty if still under threshold (no DB row written yet)
     */
    @Transactional
    public Optional<EmailSuppression> handleSoftBounce(UpsertInput input) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        Optional<EmailSuppression> existingOpt =
            repository.findByOrgIdAndRecipientHash(input.orgId, input.recipientHash);

        if (existingOpt.isEmpty()) {
            // First soft bounce: write a tracking row but do NOT
            // suppress yet. reason stays MANUAL with bounce_count=1 +
            // soft_window_started_at = now; future events increment.
            EmailSuppression row = new EmailSuppression();
            row.setOrgId(input.orgId);
            row.setChannel("email");
            row.setRecipientHash(input.recipientHash);
            row.setRecipientType(input.recipientType != null
                ? input.recipientType
                : EmailSuppression.RecipientType.EXTERNAL);
            row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);  // placeholder
            row.setFirstSeenAt(now);
            row.setLastSeenAt(now);
            row.setBounceCount(1);
            row.setSoftWindowStartedAt(now);
            row.setLastBounceSummaryRedacted(input.summaryRedacted);
            row.setLastSource(input.source);
            row.setLastProvider(input.provider);
            row.setCreatedAt(now);
            row.setUpdatedAt(now);
            // Below threshold → no active suppression yet.
            row.setSuppressedUntil(now.minusSeconds(1));  // expired marker
            repository.save(row);
            return Optional.empty();
        }

        EmailSuppression row = existingOpt.get();

        // Reset window if previous soft window is older than window days.
        if (row.getSoftWindowStartedAt() == null
            || row.getSoftWindowStartedAt().isBefore(now.minusDays(SOFT_BOUNCE_WINDOW_DAYS))) {
            row.setSoftWindowStartedAt(now);
            row.setBounceCount(1);
        } else {
            row.setBounceCount(row.getBounceCount() + 1);
        }

        row.setLastSeenAt(now);
        row.setUpdatedAt(now);
        row.setLastBounceSummaryRedacted(input.summaryRedacted);
        row.setLastSource(input.source);
        row.setLastProvider(input.provider);

        if (row.getBounceCount() >= SOFT_BOUNCE_THRESHOLD) {
            row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
            row.setSuppressedUntil(now.plusDays(SOFT_HOLD_DAYS));
            EmailSuppression saved = repository.save(row);
            log.info(
                "email suppression escalated to SOFT_BOUNCE_REPEATED org={} count={}",
                saved.getOrgId(), saved.getBounceCount()
            );
            return Optional.of(saved);
        }

        repository.save(row);
        return Optional.empty();
    }

    /**
     * Manually release a suppression entry (admin action). Returns true
     * if the row existed and was deleted; false if nothing matched.
     */
    @Transactional
    public boolean release(String orgId, String recipientHash) {
        Optional<EmailSuppression> existingOpt =
            repository.findByOrgIdAndRecipientHash(orgId, recipientHash);
        if (existingOpt.isEmpty()) {
            return false;
        }
        repository.delete(existingOpt.get());
        log.info("email suppression released org={} recipient_hash=<redacted>", orgId);
        return true;
    }

    /**
     * List active suppressions for an org.
     */
    @Transactional(readOnly = true)
    public List<EmailSuppression> listForOrg(String orgId) {
        return repository.findByOrgIdAndChannelOrderByUpdatedAtDesc(orgId, "email");
    }

    /**
     * Bounce-event upsert input. Records source + provider metadata for
     * audit trail; the service is provider-agnostic.
     */
    public static class UpsertInput {
        public final String orgId;
        public final String recipientHash;
        public final EmailSuppression.RecipientType recipientType;
        public final EmailSuppression.Reason reason;
        public final EmailSuppression.Source source;
        public final String provider;
        public final String providerMsgId;
        public final String summaryRedacted;
        public final String eventFingerprint;
        public final String actor;

        public UpsertInput(
            String orgId,
            String recipientHash,
            EmailSuppression.RecipientType recipientType,
            EmailSuppression.Reason reason,
            EmailSuppression.Source source,
            String provider,
            String providerMsgId,
            String summaryRedacted,
            String eventFingerprint,
            String actor
        ) {
            this.orgId = orgId;
            this.recipientHash = recipientHash;
            this.recipientType = recipientType;
            this.reason = reason;
            this.source = source;
            this.provider = provider;
            this.providerMsgId = providerMsgId;
            this.summaryRedacted = summaryRedacted;
            this.eventFingerprint = eventFingerprint;
            this.actor = actor;
        }
    }
}
