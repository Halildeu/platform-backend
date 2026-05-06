package com.serban.notify.adapter.inapp;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationInbox;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationInboxRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.template.RenderedMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * In-app inbox channel adapter (Faz 23.3 PR-E.2 — wires PR-E.1 inbox table
 * into ChannelAdapter pipeline).
 *
 * <p>Channel addressing: recipient-addressed (subscriber-only). Each delivery
 * target represents a single subscriber's inbox row; external recipient
 * type is rejected at planning time (no in-app inbox without a subscriber
 * identity).
 *
 * <p>Send semantics: instead of an HTTP call (like email/slack/webhook
 * adapters), this adapter inserts/updates a {@link NotificationInbox} row
 * via {@link NotificationInboxRepository}. The "delivery" is a database
 * write; success status is {@code DELIVERED} when the row persists.
 *
 * <p>Idempotency: UNIQUE (org_id, intent_id, subscriber_id) at DB layer
 * (V9 migration). If the worker dispatches the same intent×subscriber
 * twice (retry / lease recovery), the second insert returns the existing
 * row's content rather than failing — adapter treats this as DELIVERED
 * (subscriber already sees the inbox row).
 *
 * <p>Provider message id: {@code "inbox-{rowId}"} so worker / audit can
 * correlate the inbox row across the delivery and inbox tables.
 *
 * <p>Out of scope (deferred):
 * <ul>
 *   <li>WebSocket / SSE real-time push to subscriber's connected client</li>
 *   <li>TTL-based auto-archive worker (retention policy review)</li>
 *   <li>Cross-channel preference: subscriber opt-out from in-app while still
 *       receiving email (PR-E.3 SubscriberPreferenceService extension)</li>
 * </ul>
 */
@Component
public class InAppInboxAdapter implements ChannelAdapter {

    private static final Logger log = LoggerFactory.getLogger(InAppInboxAdapter.class);

    private final NotificationInboxRepository inboxRepository;
    private final NotificationIntentRepository intentRepository;

    public InAppInboxAdapter(
        NotificationInboxRepository inboxRepository,
        NotificationIntentRepository intentRepository
    ) {
        this.inboxRepository = inboxRepository;
        this.intentRepository = intentRepository;
    }

    @Override
    public String channelKey() {
        return "in-app";
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public DeliveryAttemptResult send(DeliveryTarget target, RenderedMessage message) {
        // Pre-flight validation
        String subscriberId = target.recipientId();
        if (subscriberId == null || subscriberId.isBlank()) {
            log.warn("inapp send: subscriberId missing on target hash={}", target.recipientHash());
            return DeliveryAttemptResult.failed(
                "in-app channel requires subscriber recipient (subscriberId missing)", null
            );
        }

        // targetRef encodes "intentId|orgId" pair (DeliveryPlanService composes
        // this; adapter reuses to avoid duplicate intent lookup for org_id).
        String[] parts = target.targetRef() == null ? new String[0] : target.targetRef().split("\\|", 2);
        if (parts.length != 2) {
            log.warn("inapp send: malformed targetRef on hash={}", target.recipientHash());
            return DeliveryAttemptResult.failed(
                "in-app targetRef must be 'intentId|orgId'", null
            );
        }
        String intentId = parts[0];
        String orgId = parts[1];

        // Idempotency check: row already exists → DELIVERED (no-op insert).
        Optional<NotificationInbox> existing = inboxRepository
            .findByOrgIdAndIntentIdAndSubscriberId(orgId, intentId, subscriberId);
        if (existing.isPresent()) {
            log.info("inapp DELIVERED (idempotent): id={} subscriberHash={} intent={}",
                existing.get().getId(), target.recipientHash(), intentId);
            return DeliveryAttemptResult.delivered("inbox-" + existing.get().getId());
        }

        // Lookup parent intent for topic_key + severity context (NotificationInbox
        // schema requires both NOT NULL).
        Optional<NotificationIntent> intentOpt = intentRepository
            .findByIntentIdAndOrgId(intentId, orgId);
        if (intentOpt.isEmpty()) {
            log.warn("inapp send: intent not found id={} org={}", intentId, orgId);
            return DeliveryAttemptResult.failed(
                "in-app channel: parent intent not found", null
            );
        }
        NotificationIntent intent = intentOpt.get();

        // Construct + persist inbox row
        NotificationInbox row = new NotificationInbox();
        row.setOrgId(orgId);
        row.setIntentId(intentId);
        row.setSubscriberId(subscriberId);
        row.setSubject(message.subject());
        row.setBodyText(message.bodyText());
        row.setBodyHtml(message.bodyHtml());
        row.setLocale(message.locale() != null ? message.locale() : "tr-TR");
        row.setTopicKey(intent.getTopicKey());
        row.setSeverity(intent.getSeverity().name());
        row.setState(NotificationInbox.State.UNREAD);
        // expires_at: optional; left null for now (TTL/auto-archive deferred)

        NotificationInbox saved = inboxRepository.save(row);

        log.info("inapp DELIVERED: id={} subscriberHash={} intent={}",
            saved.getId(), target.recipientHash(), intentId);

        return DeliveryAttemptResult.delivered("inbox-" + saved.getId());
    }
}
