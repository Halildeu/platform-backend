package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 #548 slice-1 step-4 — {@link TpmEnrollmentCompletionService#markConsumed} persists the V10-proven
 * device binding atomically with the CONSUMED transition (Codex {@code 019efada} decision A). Unit-level: the
 * single-active partial-unique invariant + the actual bulk revoke are DB-level (exercised by the migration +
 * the PG integration suite); here we pin the service behaviour (persist / null-device skip / revoke-before-insert).
 */
class TpmEnrollmentCompletionServiceTest {

    private static final UUID ENROLL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final byte[] AK_NAME = {0x00, 0x0b, 0x01, 0x02, 0x03};
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private final EndpointEnrollmentRepository enrollments = mock(EndpointEnrollmentRepository.class);
    private final EndpointTpmDeviceBindingRepository bindings = mock(EndpointTpmDeviceBindingRepository.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TpmEnrollmentCompletionService service =
            new TpmEnrollmentCompletionService(enrollments, bindings, clock);

    private EndpointEnrollment inProgressEnrollment() {
        EndpointEnrollment e = mock(EndpointEnrollment.class);
        when(enrollments.findById(ENROLL_ID)).thenReturn(Optional.of(e));
        return e;
    }

    private static TpmEnrollmentCompletionService.TpmBinding binding(UUID deviceId) {
        return new TpmEnrollmentCompletionService.TpmBinding(
                TENANT, deviceId, AK_NAME, "akpub-sha", "ekcert-sha", "spki-sha");
    }

    @Test
    void markConsumedTransitionsConsumedAndPersistsTheBindingAtomically() {
        EndpointEnrollment e = inProgressEnrollment();

        service.markConsumed(ENROLL_ID, binding(DEVICE));

        verify(e).setStatus(EnrollmentStatus.CONSUMED);
        verify(e).setConsumedAt(NOW);
        verify(enrollments).save(e);

        // revoke-prior runs BEFORE the insert (re-enrollment supersede; 0 rows on a first enrollment)
        verify(bindings).revokeActive(TENANT, DEVICE, NOW, "REENROLLMENT_SUPERSEDED");
        ArgumentCaptor<EndpointTpmDeviceBinding> saved = ArgumentCaptor.forClass(EndpointTpmDeviceBinding.class);
        verify(bindings).save(saved.capture());
        EndpointTpmDeviceBinding row = saved.getValue();
        assertEquals(TENANT, row.getTenantId());
        assertEquals(DEVICE, row.getDeviceId());
        assertEquals(ENROLL_ID, row.getEndpointEnrollmentId());
        assertArrayEquals(AK_NAME, row.getAkName());
        assertEquals("akpub-sha", row.getAkPubSha256());
        assertEquals("ekcert-sha", row.getEkCertSha256());
        assertEquals("spki-sha", row.getDeviceKeySpkiSha256());
        assertEquals(NOW, row.getEnrolledAt());
    }

    @Test
    void nullDeviceIdMarksConsumedButPersistsNoTrustBinding() {
        EndpointEnrollment e = inProgressEnrollment();

        service.markConsumed(ENROLL_ID, binding(null));

        verify(e).setStatus(EnrollmentStatus.CONSUMED);
        verify(enrollments).save(e);
        // a device-less enrollment writes NO binding row (and does not touch the active-binding slot)
        verify(bindings, never()).save(any());
        verify(bindings, never()).revokeActive(any(), any(), any(), any());
    }

    @Test
    void nullBindingMarksConsumedButPersistsNoTrustBinding() {
        EndpointEnrollment e = inProgressEnrollment();

        service.markConsumed(ENROLL_ID, null);

        verify(e).setStatus(EnrollmentStatus.CONSUMED);
        verify(enrollments).save(e);
        verify(bindings, never()).save(any());
        verify(bindings, never()).revokeActive(any(), any(), any(), any());
    }

    @Test
    void missingEnrollmentDeniesAndNeverWritesABinding() {
        when(enrollments.findById(ENROLL_ID)).thenReturn(Optional.empty());

        try {
            service.markConsumed(ENROLL_ID, binding(DEVICE));
        } catch (TpmAttestException expected) {
            // fail-closed: enrollment gone
        }
        verify(bindings, never()).save(any());
        verify(bindings, never()).revokeActive(eq(TENANT), eq(DEVICE), any(), any());
    }
}
