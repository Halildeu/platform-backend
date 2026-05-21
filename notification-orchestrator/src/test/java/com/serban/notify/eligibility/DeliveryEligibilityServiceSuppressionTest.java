package com.serban.notify.eligibility;

import com.serban.notify.authz.AuthzClient;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.EmailSuppression;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.preference.SubscriberPreferenceService;
import com.serban.notify.suppression.EmailSuppressionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Suppression guard integration tests for {@link
 * DeliveryEligibilityService} (Faz 23.8 M7 T4.3.b).
 *
 * <p>Covers:
 * <ul>
 *   <li>Email channel + suppression match → BLOCKED_BY_SUPPRESSION
 *       short-circuits subsequent guards (external, preference, authz)</li>
 *   <li>Email channel + no suppression → guard chain continues normally</li>
 *   <li>SMS channel + suppression service set → guard skipped (email-only)</li>
 *   <li>Suppression service null (legacy/test) → guard silently skipped</li>
 * </ul>
 */
class DeliveryEligibilityServiceSuppressionTest {

    private SubscriberPreferenceService prefService;
    private AuthzClient authzClient;
    private EmailSuppressionService suppressionService;

    @BeforeEach
    void setUp() {
        prefService = mock(SubscriberPreferenceService.class);
        authzClient = mock(AuthzClient.class);
        suppressionService = mock(EmailSuppressionService.class);
    }

    @Test
    void emailSuppressionMatchBlocksAndShortCircuits() {
        // Setup: hard-bounce row in suppression list.
        EmailSuppression row = new EmailSuppression();
        row.setOrgId("default");
        row.setReason(EmailSuppression.Reason.HARD_BOUNCE);
        row.setBounceCount(2);
        when(suppressionService.isCurrentlyActive("default", "rh-1"))
            .thenReturn(Optional.of(row));

        DeliveryEligibilityService svc = serviceWithSuppression();
        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), emailSubscriberTarget());

        assertThat(decision.blocked()).isTrue();
        assertThat(decision.status()).isEqualTo(NotificationDelivery.Status.BLOCKED_BY_SUPPRESSION);
        assertThat(decision.policy()).isEqualTo("email_suppression_hard_bounce");
        assertThat(decision.reason()).contains("HARD_BOUNCE");

        // Short-circuit: external policy + preference + authz NOT consulted.
        verify(prefService, never()).evaluate(ArgumentMatchers.any(), anyString(), anyString());
        verify(authzClient, never()).check(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void emailNoSuppressionContinuesGuardChain() {
        when(suppressionService.isCurrentlyActive(anyString(), anyString()))
            .thenReturn(Optional.empty());
        when(prefService.evaluate(ArgumentMatchers.any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        DeliveryEligibilityService svc = serviceWithSuppression();
        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), emailSubscriberTarget());

        assertThat(decision.blocked()).isFalse();
        // Guards consulted (we already verified short-circuit when matched
        // in the other test; this verifies the no-match path lets the
        // chain through).
        verify(suppressionService).isCurrentlyActive("default", "rh-1");
    }

    @Test
    void smsChannelSkipsSuppressionGuard() {
        DeliveryEligibilityService svc = serviceWithSuppression();
        when(prefService.evaluate(ArgumentMatchers.any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), smsSubscriberTarget());

        assertThat(decision.blocked()).isFalse();
        // SMS path: suppression service NOT consulted at all (email-only).
        verify(suppressionService, never()).isCurrentlyActive(anyString(), anyString());
    }

    @Test
    void nullSuppressionServiceSilentlySkipsGuard() {
        // Legacy/test path: setter not called → email guard silently skipped.
        DeliveryEligibilityService svc = new DeliveryEligibilityService(
            prefService, authzClient, true, true
        );
        // suppressionService NOT injected — null.
        when(prefService.evaluate(ArgumentMatchers.any(), anyString(), anyString()))
            .thenReturn(SubscriberPreferenceService.PreferenceDecision.allow("ok"));
        when(authzClient.check(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenReturn(AuthzClient.AuthzDecision.allow("tuple_match"));

        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), emailSubscriberTarget());

        assertThat(decision.blocked()).isFalse();
    }

    @Test
    void suppressionMatchPolicyEncodesReasonLowercase() {
        // Spam complaint -> policy "email_suppression_spam_complaint"
        EmailSuppression row = new EmailSuppression();
        row.setOrgId("default");
        row.setReason(EmailSuppression.Reason.SPAM_COMPLAINT);
        row.setBounceCount(1);
        when(suppressionService.isCurrentlyActive("default", "rh-1"))
            .thenReturn(Optional.of(row));

        DeliveryEligibilityService svc = serviceWithSuppression();
        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), emailSubscriberTarget());

        assertThat(decision.policy()).isEqualTo("email_suppression_spam_complaint");
    }

    @Test
    void suppressionMatchSoftBounceRepeatedReportsCount() {
        EmailSuppression row = new EmailSuppression();
        row.setOrgId("default");
        row.setReason(EmailSuppression.Reason.SOFT_BOUNCE_REPEATED);
        row.setBounceCount(4);
        when(suppressionService.isCurrentlyActive("default", "rh-1"))
            .thenReturn(Optional.of(row));

        DeliveryEligibilityService svc = serviceWithSuppression();
        var decision = svc.evaluate(intent(), externalAllowedTemplate(true), emailSubscriberTarget());

        assertThat(decision.policy()).isEqualTo("email_suppression_soft_bounce_repeated");
        assertThat(decision.reason()).contains("bounce_count=4");
    }

    private DeliveryEligibilityService serviceWithSuppression() {
        DeliveryEligibilityService svc = new DeliveryEligibilityService(
            prefService, authzClient, true, true
        );
        svc.setEmailSuppressionService(suppressionService);
        return svc;
    }

    private NotificationIntent intent() {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("test");
        i.setOrgId("default");
        i.setTopicKey("marketing.campaign");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setTemplateId("marketing-campaign");
        return i;
    }

    private NotificationTemplate externalAllowedTemplate(boolean externalAllowed) {
        NotificationTemplate t = new NotificationTemplate();
        t.setTemplateId("marketing-campaign");
        t.setExternalAllowed(externalAllowed);
        return t;
    }

    private DeliveryTarget emailSubscriberTarget() {
        return new DeliveryTarget("email", "subscriber", "1204", "rh-1", "u@x.com", "smtp-default");
    }

    private DeliveryTarget smsSubscriberTarget() {
        return new DeliveryTarget("sms", "subscriber", "1204", "rh-1", "+905551112233", "netgsm-default");
    }
}
