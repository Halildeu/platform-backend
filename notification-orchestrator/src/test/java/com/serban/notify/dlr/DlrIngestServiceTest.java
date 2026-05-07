package com.serban.notify.dlr;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * DlrIngestService unit test (Faz 23.4 PR-F).
 *
 * <p>Test scope:
 * <ul>
 *   <li>NetGSM code 00 → DELIVERED + delivered_at set + audit emit</li>
 *   <li>NetGSM code 04/05/16/17/70 → FAILED + permanent_failure_at set</li>
 *   <li>NetGSM unknown code → NOOP (no status change, audit-only)</li>
 *   <li>Provider_msg_id not found → NOT_FOUND result + warn log</li>
 *   <li>Idempotency: already DELIVERED + late FAILED DLR → no downgrade</li>
 *   <li>Audit always emits (compliance trail) even on no-op</li>
 *   <li>Intent missing → audit skipped but row mutation still applied</li>
 * </ul>
 */
class DlrIngestServiceTest {

    private NotificationDeliveryRepository deliveryRepo;
    private NotificationIntentRepository intentRepo;
    private AuditEventPublisher audit;
    private DlrIngestService service;

    @BeforeEach
    void setUp() {
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        intentRepo = mock(NotificationIntentRepository.class);
        audit = mock(AuditEventPublisher.class);
        service = new DlrIngestService(deliveryRepo, intentRepo, audit);
    }

    // ─── Happy path: code 00 → DELIVERED ─────────────────────────────────

    @Test
    void code00DeliversAndSetsDeliveredAt() {
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.PENDING);
        when(deliveryRepo.findFirstByProviderMsgId("netgsm-abc-1"))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId("intent-1")).thenReturn(Optional.of(stubIntent()));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        assertThat(d.getDeliveredAt()).isNotNull();
        verify(deliveryRepo).save(d);
        verify(audit).publish(
            eq("DELIVERY_DLR_RECEIVED"),
            any(NotificationIntent.class),
            anyString(),
            eq("sms"),
            any()
        );
    }

    // ─── Permanent failure codes ─────────────────────────────────────────

    @Test
    void code04CarrierRejectFails() {
        verifyPermanentFailureCode("04", "carrier reject");
    }

    @Test
    void code05UndeliverableFails() {
        verifyPermanentFailureCode("05", "undeliverable");
    }

    @Test
    void code16ExpiredFails() {
        verifyPermanentFailureCode("16", "expired");
    }

    @Test
    void code17IysOptOutFails() {
        verifyPermanentFailureCode("17", "iys opt-out");
    }

    @Test
    void code70IysOptOutVariantFails() {
        verifyPermanentFailureCode("70", "iys opt-out variant");
    }

    private void verifyPermanentFailureCode(String code, String desc) {
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.PENDING);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(stubIntent()));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", code, desc);

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        assertThat(result.currentStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.FAILED);
        assertThat(d.getPermanentFailureAt()).isNotNull();
        assertThat(d.getFailureReason()).contains("dlr netgsm code=" + code);
    }

    // ─── Transient/unknown codes ─────────────────────────────────────────

    @Test
    void unknownCodeNoOpsButAuditEmits() {
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.PENDING);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(stubIntent()));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "99", "?");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.PENDING);
        verify(deliveryRepo, never()).save(d);
        // Audit still emitted for compliance trail
        verify(audit).publish(eq("DELIVERY_DLR_RECEIVED"), any(), any(), any(), any());
    }

    // ─── Not found ───────────────────────────────────────────────────────

    @Test
    void unknownProviderMsgIdReturnsNotFound() {
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.empty());

        DlrIngestService.DlrResult result = service.ingestNetgsm("ghost-jobid", "00", "OK");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOT_FOUND);
        assertThat(result.providerMsgId()).isEqualTo("netgsm-ghost-jobid");
        assertThat(result.currentStatus()).isNull();
        verify(deliveryRepo, never()).save(any());
        verifyNoInteractions(audit);
    }

    // ─── Idempotency / forward-only ──────────────────────────────────────

    @Test
    void alreadyDeliveredIgnoresLateFailureCodeNoDowngrade() {
        // Forward-only: late carrier-side FAILED DLR for already-DELIVERED row
        // must NOT downgrade status. Audit still records the event.
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.DELIVERED);
        d.setDeliveredAt(java.time.OffsetDateTime.now().minusMinutes(5));
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(stubIntent()));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "16", "expired late");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        verify(deliveryRepo, never()).save(d);
        verify(audit).publish(eq("DELIVERY_DLR_RECEIVED"), any(), any(), any(), any());
    }

    @Test
    void duplicate00DlrOnAlreadyDeliveredRowIsNoOp() {
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.DELIVERED);
        d.setDeliveredAt(java.time.OffsetDateTime.now().minusMinutes(2));
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.of(stubIntent()));

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK retry");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.NOOP);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        verify(deliveryRepo, never()).save(d);
    }

    // ─── Edge: intent not found ──────────────────────────────────────────

    @Test
    void intentMissingSkipsAuditButStillMutates() {
        NotificationDelivery d = stubDelivery(NotificationDelivery.Status.PENDING);
        when(deliveryRepo.findFirstByProviderMsgId(anyString()))
            .thenReturn(Optional.of(d));
        when(intentRepo.findByIntentId(anyString())).thenReturn(Optional.empty());

        DlrIngestService.DlrResult result = service.ingestNetgsm("abc-1", "00", "OK");

        assertThat(result.action()).isEqualTo(DlrIngestService.DlrAction.UPDATED);
        assertThat(d.getStatus()).isEqualTo(NotificationDelivery.Status.DELIVERED);
        verify(deliveryRepo).save(d);
        verifyNoInteractions(audit);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────

    private static NotificationDelivery stubDelivery(NotificationDelivery.Status status) {
        NotificationDelivery d = new NotificationDelivery();
        d.setId(42L);
        d.setIntentId("intent-1");
        d.setChannel("sms");
        d.setRecipientType(NotificationDelivery.RecipientType.SUBSCRIBER);
        d.setRecipientId("sub-1");
        d.setRecipientHash("hash-x");
        d.setProvider("netgsm-default");
        d.setProviderMsgId("netgsm-abc-1");
        d.setStatus(status);
        return d;
    }

    private static NotificationIntent stubIntent() {
        NotificationIntent i = new NotificationIntent();
        i.setIntentId("intent-1");
        i.setOrgId("default");
        i.setTopicKey("test.topic");
        i.setSeverity(NotificationIntent.Severity.info);
        i.setDataClassification(NotificationIntent.DataClassification.transactional);
        i.setTemplateId("t");
        i.setTemplateVersion(1);
        i.setLocale("tr-TR");
        return i;
    }
}
