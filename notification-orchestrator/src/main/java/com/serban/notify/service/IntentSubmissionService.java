package com.serban.notify.service;

import com.serban.notify.api.dto.SubmitIntentRequest;
import com.serban.notify.api.dto.SubmitIntentResponse;
import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.exception.IntakeCapacityExceededException;
import com.serban.notify.redaction.PiiRedactor;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.template.TemplateResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * IntentSubmissionService — orchestrator core for Faz 23.1 PR2 (Codex 019df9ae absorb).
 *
 * <p>Submit pipeline (single transaction):
 * <ol>
 *   <li>Bounded intake check (intake.maxPending vs PENDING count) — Codex Q3</li>
 *   <li>Idempotency advisory lock + active key lookup (Codex Q1)</li>
 *   <li>If duplicate: return original intent_id (REPLAYED)</li>
 *   <li>Template resolve only — no render (Codex Q2)</li>
 *   <li>Compute recipient_hash for first recipient (audit primary)</li>
 *   <li>NotificationIntent INSERT (status=PENDING)</li>
 *   <li>IdempotencyKey INSERT (24h TTL)</li>
 *   <li>AuditEventPublisher INTENT_CREATED (PII-redacted)</li>
 *   <li>Return ACCEPTED + tracking URL</li>
 * </ol>
 *
 * <p>dispatch.enabled=false: status PENDING'de kalır, worker yok (PR4).
 * Channel adapter yok (PR3). Render yok (PR3).
 */
@Service
public class IntentSubmissionService {

    private static final Logger log = LoggerFactory.getLogger(IntentSubmissionService.class);

    private final IdempotencyService idempotencyService;
    private final TemplateResolver templateResolver;
    private final NotificationIntentRepository intentRepository;
    private final AuditEventPublisher auditPublisher;
    private final PiiRedactor piiRedactor;
    private final NotifyConfig config;

    public IntentSubmissionService(
        IdempotencyService idempotencyService,
        TemplateResolver templateResolver,
        NotificationIntentRepository intentRepository,
        AuditEventPublisher auditPublisher,
        PiiRedactor piiRedactor,
        NotifyConfig config
    ) {
        this.idempotencyService = idempotencyService;
        this.templateResolver = templateResolver;
        this.intentRepository = intentRepository;
        this.auditPublisher = auditPublisher;
        this.piiRedactor = piiRedactor;
        this.config = config;
    }

    /**
     * Submit notification intent transactionally.
     *
     * @param request validated DTO from controller
     * @return ACCEPTED if new, REPLAYED if duplicate idempotency_key
     */
    @Transactional
    public SubmitIntentResponse submit(SubmitIntentRequest request) {
        // Step 1: Bounded intake check (Codex Q3 PARTIAL)
        long pendingCount = intentRepository.countByStatus(NotificationIntent.Status.PENDING);
        if (pendingCount >= config.intake().maxPending()) {
            throw new IntakeCapacityExceededException(
                "intake.maxPending exceeded: " + pendingCount + " / " + config.intake().maxPending()
                    + " (dispatch.enabled=" + config.dispatch().enabled() + ")"
            );
        }

        // Step 2: Idempotency advisory lock + lookup (Codex Q1)
        Optional<String> existingIntentId =
            idempotencyService.findActiveOriginal(request.orgId(), request.idempotencyKey());
        if (existingIntentId.isPresent()) {
            log.info("idempotency replay: orgId={} key={} originalIntentId={}",
                request.orgId(), request.idempotencyKey(), existingIntentId.get());
            return SubmitIntentResponse.replayed(existingIntentId.get());
        }

        // Step 3: Template resolve only (Codex Q2)
        NotificationTemplate resolved = templateResolver.resolve(
            request.template().templateId(),
            request.template().locale(),
            request.template().version()
        );

        // Step 4: Compute primary recipient hash (audit single primary; full
        // fan-out to delivery rows PR3+'da)
        SubmitIntentRequest.RecipientRef primary = request.recipients().get(0);
        String recipientHash = computeRecipientHash(request.orgId(), primary);

        // Step 5: Persist intent
        NotificationIntent intent = mapToEntity(request, resolved);
        intentRepository.save(intent);

        // Step 6: Register idempotency key
        idempotencyService.registerKey(request.orgId(), request.idempotencyKey(), request.intentId());

        // Step 7: Audit event INTENT_CREATED (PII-redacted via whitelist)
        auditPublisher.publishIntentCreated(intent, recipientHash, primary.type().name());

        log.info("intent accepted: intentId={} orgId={} topic={} severity={} "
            + "channels={} dispatch.enabled={}",
            request.intentId(), request.orgId(), request.topicKey(),
            request.severity(), request.channels(), config.dispatch().enabled());

        return SubmitIntentResponse.accepted(request.intentId());
    }

    /** Build NotificationIntent from validated request + resolved template. */
    private NotificationIntent mapToEntity(SubmitIntentRequest request, NotificationTemplate template) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(request.intentId());
        intent.setCorrelationId(request.correlationId());
        intent.setOrgId(request.orgId());
        intent.setTopicKey(request.topicKey());
        intent.setSeverity(request.severity());
        intent.setDataClassification(request.dataClassification());
        intent.setPayload(request.payload());
        intent.setTemplateId(template.getTemplateId());
        intent.setTemplateVersion(template.getVersion());  // resolved version
        intent.setLocale(template.getLocale());            // resolved locale (fallback chain)
        intent.setChannels(request.channels().toArray(new String[0]));
        intent.setChannelRouting(request.channelRouting());
        intent.setScheduledAt(request.scheduledAt());
        intent.setExpireAt(request.expireAt());
        intent.setMetadata(request.metadata());
        intent.setPreferenceOverride(request.preferenceOverride());
        intent.setStatus(NotificationIntent.Status.PENDING);
        return intent;
    }

    private String computeRecipientHash(String orgId, SubmitIntentRequest.RecipientRef ref) {
        String type = ref.type().name();
        String value;
        if (ref.type() == SubmitIntentRequest.RecipientRef.Type.subscriber) {
            value = ref.subscriberId();
        } else if (ref.email() != null && !ref.email().isBlank()) {
            value = ref.email();
        } else if (ref.phone() != null && !ref.phone().isBlank()) {
            value = ref.phone();
        } else {
            value = ref.subscriberId() != null ? ref.subscriberId() : "<unknown>";
        }
        return piiRedactor.hashRecipient(orgId, type, value);
    }
}
