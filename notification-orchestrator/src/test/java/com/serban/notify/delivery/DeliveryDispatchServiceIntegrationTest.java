package com.serban.notify.delivery;

import com.serban.notify.AbstractPostgresTest;
import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.SlackWebhookAdapter;
import com.serban.notify.adapter.SmtpAdapter;
import com.serban.notify.adapter.WebhookEgressAdapter;
import com.serban.notify.domain.AuditEvent;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.AuditEventRepository;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import com.serban.notify.template.RenderedMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DeliveryDispatchService integration test (Codex 019df9ae Q1 REVISE absorb).
 *
 * <p>Test scope:
 * <ul>
 *   <li>Internal direct-invoke pipeline (PR3 — no auto-dispatch)</li>
 *   <li>Template lookup → render → adapter.send → delivery row + audit event</li>
 *   <li>Status transitions: PENDING → PROCESSING → COMPLETED (all delivered)</li>
 *   <li>Partial failure: stays PROCESSING (PR4 worker decides)</li>
 *   <li>Adapter exception → RETRY result (no propagation)</li>
 * </ul>
 *
 * <p>Strategy: real Spring context + Testcontainers PG; channel adapter
 * implementations replaced with {@link MockBean} so we control adapter return
 * values without real SMTP/HTTP.
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
class DeliveryDispatchServiceIntegrationTest extends AbstractPostgresTest {

    @Autowired DeliveryDispatchService dispatcher;
    @Autowired NotificationTemplateRepository templateRepo;
    @Autowired NotificationIntentRepository intentRepo;
    @Autowired NotificationDeliveryRepository deliveryRepo;
    @Autowired AuditEventRepository auditRepo;

    @MockBean SmtpAdapter smtpAdapter;
    @MockBean SlackWebhookAdapter slackAdapter;
    @MockBean WebhookEgressAdapter webhookAdapter;

    @BeforeEach
    void seedTemplate() {
        if (templateRepo.findByTemplateIdAndVersionAndLocale("dispatch-test", 1, "tr-TR").isPresent()) {
            return;
        }
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("dispatch-test");
        t.setVersion(1);
        t.setLocale("tr-TR");
        t.setSubject("Sub [[${vars.user_name}]]");
        t.setBodyText("Hello [[${vars.user_name}]]");
        t.setActive(true);
        t.setCreatedBy("test");
        templateRepo.save(t);

        when(smtpAdapter.channelKey()).thenReturn("email");
        when(slackAdapter.channelKey()).thenReturn("slack");
        when(webhookAdapter.channelKey()).thenReturn("webhook");
    }

    @Test
    void dispatchSingleEmailDelivered() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "subscriber", "1204", "rh-1", "user@example.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1@host>")
        );

        int attempted = dispatcher.dispatchPlanned(intent, List.of(target));

        assertThat(attempted).isEqualTo(1);
        verify(smtpAdapter, times(1)).send(any(), any(RenderedMessage.class));

        // Intent COMPLETED
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.COMPLETED);

        // Delivery row persisted
        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        NotificationDelivery d = deliveries.get(0);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(d.getChannel()).isEqualTo("email");
        assertThat(d.getProvider()).isEqualTo("smtp-default");
        assertThat(d.getProviderMsgId()).isEqualTo("<msg-1@host>");
        assertThat(d.getDeliveredAt()).isNotNull();
        assertThat(d.getRecipientType()).isEqualTo(NotificationDelivery.RecipientType.SUBSCRIBER);

        // Audit events: ATTEMPTED + SUCCEEDED
        List<AuditEvent> events = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(intent.getCorrelationId());
        assertThat(events).extracting(AuditEvent::getEventType)
            .contains("DELIVERY_ATTEMPTED", "DELIVERY_SUCCEEDED");
    }

    @Test
    void dispatchPermanentFailureKeepsProcessing() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget target = new DeliveryTarget(
            "email", "external", null, "rh-2", "user2@example.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.failed("550 No such user", 500)
        );

        dispatcher.dispatchPlanned(intent, List.of(target));

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        // PR3: permanent failure keeps PROCESSING; PR4 worker decides terminal state
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        NotificationDelivery d = deliveries.get(0);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(d.getFailureReason()).contains("550");

        List<AuditEvent> events = auditRepo.findByCorrelationIdOrderByOccurredAtAsc(intent.getCorrelationId());
        assertThat(events).extracting(AuditEvent::getEventType)
            .contains("DELIVERY_ATTEMPTED", "DELIVERY_FAILED");
    }

    @Test
    void dispatchRetryKeepsProcessing() {
        NotificationIntent intent = saveIntent("webhook");
        DeliveryTarget target = new DeliveryTarget(
            "webhook", "channel", null, "rh-w", "https://hook.local/x", "webhook-default"
        );
        when(webhookAdapter.send(any(), any())).thenReturn(
            ChannelAdapter.DeliveryAttemptResult.retry("HTTP 503", 503)
        );

        dispatcher.dispatchPlanned(intent, List.of(target));

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.RETRY);
    }

    @Test
    void dispatchAdapterExceptionTreatedAsRetry() {
        NotificationIntent intent = saveIntent("slack");
        DeliveryTarget target = new DeliveryTarget(
            "slack", "channel", null, "rh-s", "https://hooks.slack/x", "slack-default"
        );
        when(slackAdapter.send(any(), any())).thenThrow(new RuntimeException("boom"));

        // Must NOT propagate; pipeline handles
        dispatcher.dispatchPlanned(intent, List.of(target));

        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.get(0).getStatus()).isEqualTo(NotificationDelivery.Status.RETRY);

        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);
    }

    @Test
    void dispatchMultiTargetMixedResults() {
        NotificationIntent intent = saveIntent("email");
        DeliveryTarget t1 = new DeliveryTarget(
            "email", "subscriber", "1", "rh-a", "a@x.com", "smtp-default"
        );
        DeliveryTarget t2 = new DeliveryTarget(
            "email", "subscriber", "2", "rh-b", "b@x.com", "smtp-default"
        );
        when(smtpAdapter.send(any(), any()))
            .thenReturn(ChannelAdapter.DeliveryAttemptResult.delivered("<msg-1>"))
            .thenReturn(ChannelAdapter.DeliveryAttemptResult.bounced("550 hard bounce"));

        int attempted = dispatcher.dispatchPlanned(intent, List.of(t1, t2));

        assertThat(attempted).isEqualTo(2);
        List<NotificationDelivery> deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
        assertThat(deliveries).hasSize(2);
        assertThat(deliveries).extracting(NotificationDelivery::getStatus)
            .containsExactlyInAnyOrder(
                NotificationDelivery.Status.DELIVERED,
                NotificationDelivery.Status.BOUNCED
            );

        // BOUNCED is permanent fail → intent stays PROCESSING (PR4 decides)
        NotificationIntent reloaded = intentRepo.findByIntentId(intent.getIntentId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(NotificationIntent.Status.PROCESSING);
    }

    private NotificationIntent saveIntent(String channel) {
        NotificationIntent intent = new NotificationIntent();
        intent.setIntentId(UUID.randomUUID().toString());
        intent.setCorrelationId("trace-" + UUID.randomUUID().toString().substring(0, 8));
        intent.setOrgId("default");
        intent.setTopicKey("dispatch.test");
        intent.setSeverity(NotificationIntent.Severity.info);
        intent.setDataClassification(NotificationIntent.DataClassification.transactional);
        intent.setPayload(Map.of("user_name", "Halil"));
        intent.setTemplateId("dispatch-test");
        intent.setTemplateVersion(1);
        intent.setLocale("tr-TR");
        intent.setChannels(new String[] { channel });
        intent.setStatus(NotificationIntent.Status.PENDING);
        return intentRepo.save(intent);
    }
}
