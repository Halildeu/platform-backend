package com.serban.notify.inbox;

import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.exception.InvalidRequestException;
import com.serban.notify.repository.NotificationInboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * In-app inbox service (Faz 23.3 PR-E.1).
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>List subscriber's active inbox (paged, newest-first)</li>
 *   <li>Compute unread badge count</li>
 *   <li>State transitions: mark-as-read, archive</li>
 * </ul>
 *
 * <p>Tenancy invariant: every operation requires {@code orgId + subscriberId}
 * pair from caller (controller resolves from JWT subject claim). Repository
 * filters apply both — defense-in-depth for cross-tenant access.
 *
 * <p>Out of scope (this PR):
 * <ul>
 *   <li>Inbox row creation (intent fan-out hook) — PR-E.2</li>
 *   <li>WS/SSE real-time push — PR-E.2</li>
 *   <li>Bulk operations (mark-all-read, bulk-archive) — deferred</li>
 * </ul>
 */
@Service
public class InboxService {

    private static final Logger log = LoggerFactory.getLogger(InboxService.class);

    /** Pagination guardrail (page size cap) — prevent unbounded memory loads. */
    private static final int MAX_PAGE_SIZE = 100;

    /** Default page size for GET /inbox/me. */
    private static final int DEFAULT_PAGE_SIZE = 20;

    private final NotificationInboxRepository inboxRepository;
    private final InboxEventPublisher eventPublisher;

    public InboxService(
        NotificationInboxRepository inboxRepository,
        InboxEventPublisher eventPublisher
    ) {
        this.inboxRepository = inboxRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * List subscriber's active inbox (UNREAD + READ; ARCHIVED filtered out).
     *
     * @param orgId tenant scope
     * @param subscriberId from JWT subject claim
     * @param page 0-indexed page number
     * @param size requested page size (clamped to {@code [1, MAX_PAGE_SIZE]})
     * @return paged inbox entries newest-first
     */
    @Transactional(readOnly = true)
    public Page<NotificationInbox> listActive(String orgId, String subscriberId, int page, int size) {
        validateTenancy(orgId, subscriberId);
        int safeSize = Math.max(1, Math.min(size > 0 ? size : DEFAULT_PAGE_SIZE, MAX_PAGE_SIZE));
        int safePage = Math.max(0, page);
        Pageable pageable = PageRequest.of(safePage, safeSize);
        return inboxRepository.findActiveBySubscriber(orgId, subscriberId, pageable);
    }

    /**
     * Unread count for badge display (real-time refresh trigger PR-E.2 WS).
     */
    @Transactional(readOnly = true)
    public long unreadCount(String orgId, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        return inboxRepository.countUnreadBySubscriber(orgId, subscriberId);
    }

    /**
     * Single inbox row lookup (tenancy-guarded).
     */
    @Transactional(readOnly = true)
    public Optional<NotificationInbox> findById(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    /**
     * Mark inbox row as READ (idempotent).
     *
     * <p>State machine: UNREAD → READ. No-op if already READ or ARCHIVED.
     * Returns the post-mutation state via re-fetch.
     *
     * @return updated inbox row, empty if id not found OR cross-tenant access
     */
    @Transactional
    public Optional<NotificationInbox> markAsRead(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        // Tenancy probe first — avoids leaking 404 vs 403 distinction.
        Optional<NotificationInbox> current =
            inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
        if (current.isEmpty()) return Optional.empty();
        int affected = inboxRepository.markAsRead(orgId, id, subscriberId, OffsetDateTime.now());
        log.info("inbox.mark_read: orgId={} subscriberId={} id={} affected={}",
            orgId, subscriberId, id, affected);
        // PR-E.3: emit event (badge count changed; SSE controller broadcasts).
        // Only on actual mutation (affected > 0); idempotent re-call no-op.
        if (affected > 0) {
            eventPublisher.publishInboxUpdated(orgId, subscriberId);
        }
        // Re-fetch for post-state (trigger may have set read_at).
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    /**
     * Archive inbox row (idempotent terminal transition).
     *
     * <p>State machine: UNREAD/READ → ARCHIVED. No-op if already ARCHIVED.
     * KVKK erasure handles permanent deletion via existing erasure flow;
     * archive is soft-delete (audit trail preserved).
     */
    @Transactional
    public Optional<NotificationInbox> archive(String orgId, Long id, String subscriberId) {
        validateTenancy(orgId, subscriberId);
        if (id == null) return Optional.empty();
        Optional<NotificationInbox> current =
            inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
        if (current.isEmpty()) return Optional.empty();
        int affected = inboxRepository.archive(orgId, id, subscriberId, OffsetDateTime.now());
        log.info("inbox.archive: orgId={} subscriberId={} id={} affected={}",
            orgId, subscriberId, id, affected);
        // PR-E.3: emit event if state actually transitioned (UNREAD → ARCHIVED
        // changes badge count; READ → ARCHIVED doesn't but list shrinks)
        if (affected > 0) {
            eventPublisher.publishInboxUpdated(orgId, subscriberId);
        }
        return inboxRepository.findByOrgIdAndIdAndSubscriberId(orgId, id, subscriberId);
    }

    /**
     * Bulk mark-all-read (Faz 23.5 PR1).
     *
     * <p>Flips every UNREAD row owned by the subscriber whose
     * {@code created_at <= cutoff} to READ. The {@code cutoff} parameter
     * is the server-side request-start timestamp passed by the
     * controller — using a server clock ensures monotonic boundaries
     * free of client clock skew, and the WHERE-clause predicate
     * guarantees that notifications arriving between request acceptance
     * and the UPDATE are not collateral-marked-as-read.
     *
     * <p>Idempotent: a follow-up call with no UNREAD rows returns
     * {@code 0}. Emits {@link InboxEventPublisher#publishInboxUpdated}
     * once at the end (not per row) so SSE subscribers see a single
     * {@code unread-count} update with the post-bulk total.
     *
     * @param orgId tenant id
     * @param subscriberId subscriber whose inbox is being swept
     * @param cutoff inclusive upper bound on {@code created_at} of rows
     *               eligible for the transition
     * @return {@link BulkMarkAllReadResult} describing how many rows
     *         flipped and which cutoff was applied
     */
    @Transactional
    public BulkMarkAllReadResult markAllAsRead(
        String orgId,
        String subscriberId,
        OffsetDateTime cutoff
    ) {
        validateTenancy(orgId, subscriberId);
        if (cutoff == null) {
            throw new InvalidRequestException("cutoff required");
        }
        OffsetDateTime now = OffsetDateTime.now();
        int affected = inboxRepository.markAllAsRead(orgId, subscriberId, now, cutoff);
        log.info(
            "inbox.mark_all_read: orgId={} subscriberId={} cutoff={} affected={}",
            orgId, subscriberId, cutoff, affected
        );
        // PR-E.3 / PR-E.4: emit event so SSE subscribers get the post-bulk
        // unread count. Only when something actually flipped — calling
        // with no UNREAD rows produces a no-op event-wise too.
        if (affected > 0) {
            eventPublisher.publishInboxUpdated(orgId, subscriberId);
        }
        return new BulkMarkAllReadResult(affected, cutoff);
    }

    /**
     * Result of a {@link #markAllAsRead} call. Echoed back to the caller
     * so a UI can surface "13 bildirim okundu işaretlendi" feedback and
     * also know the cutoff that was actually applied (operator audit).
     */
    public record BulkMarkAllReadResult(int updatedCount, OffsetDateTime cutoff) {}

    private static void validateTenancy(String orgId, String subscriberId) {
        if (orgId == null || orgId.isBlank()) {
            throw new InvalidRequestException("orgId required");
        }
        if (subscriberId == null || subscriberId.isBlank()) {
            throw new InvalidRequestException("subscriberId required");
        }
    }
}
