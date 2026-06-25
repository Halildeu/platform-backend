package com.example.endpointadmin.tpmattest;

import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointTpmDeviceBinding;
import com.example.endpointadmin.model.EnrollmentStatus;
import com.example.endpointadmin.repository.EndpointEnrollmentRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceBindingRepository;
import com.example.endpointadmin.security.TpmVaultCertExtractor;
import com.example.endpointadmin.service.TpmDeviceCompletionService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 #548 Phase 1.5 (Codex {@code 019eff93}) — {@link TpmEnrollmentCompletionService#markConsumed}
 * transitions CONSUMED, delegates device resolution to {@link TpmDeviceCompletionService} (device-less →
 * created/adopted; pre-bound → asserted), and persists the V10-proven binding against the RESOLVED device id —
 * all atomically. The DB partial-unique invariants are exercised by the PG integration suite; here we pin the
 * orchestration (delegate wiring + resolved-device binding + deny propagation).
 */
class TpmEnrollmentCompletionServiceTest {

    private static final UUID ENROLL_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID TENANT = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID RESOLVED_DEVICE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID PRE_BOUND = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final byte[] AK_NAME = {0x00, 0x0b, 0x01, 0x02, 0x03};
    private static final String EK_PUB = "a".repeat(64);
    private static final String EK_CERT = "b".repeat(64);
    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);

    private final EndpointEnrollmentRepository enrollments = mock(EndpointEnrollmentRepository.class);
    private final EndpointTpmDeviceBindingRepository bindings = mock(EndpointTpmDeviceBindingRepository.class);
    private final TpmDeviceCompletionService deviceCompletion = mock(TpmDeviceCompletionService.class);
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final TpmEnrollmentCompletionService service =
            new TpmEnrollmentCompletionService(enrollments, bindings, deviceCompletion, clock);

    private EndpointEnrollment inProgressEnrollment() {
        EndpointEnrollment e = mock(EndpointEnrollment.class);
        when(enrollments.findById(ENROLL_ID)).thenReturn(Optional.of(e));
        return e;
    }

    private static TpmVaultCertExtractor.ParsedVaultCert vaultCert() {
        return new TpmVaultCertExtractor.ParsedVaultCert(
                EK_PUB, "serial", "thumb", "issuer", "subject", NOW, NOW.plusSeconds(86_400));
    }

    private static TpmEnrollmentCompletionService.TpmCompletion completion(UUID scopeDeviceId) {
        return new TpmEnrollmentCompletionService.TpmCompletion(
                TENANT, scopeDeviceId, EK_PUB, EK_CERT, vaultCert(), AK_NAME, "akpub-sha", "spki-sha");
    }

    @Test
    void markConsumed_deviceLess_resolvesDeviceAndPersistsBindingAtomically() {
        EndpointEnrollment e = inProgressEnrollment();
        when(deviceCompletion.complete(eq(TENANT), eq(EK_PUB), any(), eq(e), isNull(), eq(NOW)))
                .thenReturn(RESOLVED_DEVICE);

        service.markConsumed(ENROLL_ID, completion(null));

        verify(e).setStatus(EnrollmentStatus.CONSUMED);
        verify(e).setConsumedAt(NOW);
        verify(enrollments).save(e);

        // The binding keys on the RESOLVED device (device-completion created/adopted it), not the null scope.
        verify(bindings).revokeActive(TENANT, RESOLVED_DEVICE, NOW, "REENROLLMENT_SUPERSEDED");
        ArgumentCaptor<EndpointTpmDeviceBinding> saved = ArgumentCaptor.forClass(EndpointTpmDeviceBinding.class);
        verify(bindings).save(saved.capture());
        EndpointTpmDeviceBinding row = saved.getValue();
        assertEquals(TENANT, row.getTenantId());
        assertEquals(RESOLVED_DEVICE, row.getDeviceId());
        assertEquals(ENROLL_ID, row.getEndpointEnrollmentId());
        assertArrayEquals(AK_NAME, row.getAkName());
        assertEquals("akpub-sha", row.getAkPubSha256());
        assertEquals(EK_CERT, row.getEkCertSha256()); // EK cert digest is the L1-bound value (not L2 env.ekCert)
        assertEquals("spki-sha", row.getDeviceKeySpkiSha256());
        assertEquals(NOW, row.getEnrolledAt());
    }

    @Test
    void markConsumed_preBound_persistsBindingWithResolvedDevice() {
        EndpointEnrollment e = inProgressEnrollment();
        when(deviceCompletion.complete(eq(TENANT), eq(EK_PUB), any(), eq(e), eq(PRE_BOUND), eq(NOW)))
                .thenReturn(RESOLVED_DEVICE);

        service.markConsumed(ENROLL_ID, completion(PRE_BOUND));

        verify(deviceCompletion).complete(eq(TENANT), eq(EK_PUB), any(), eq(e), eq(PRE_BOUND), eq(NOW));
        verify(bindings).revokeActive(TENANT, RESOLVED_DEVICE, NOW, "REENROLLMENT_SUPERSEDED");
        verify(bindings).save(any());
    }

    @Test
    void markConsumed_completionDeny_propagatesAndWritesNoBinding() {
        inProgressEnrollment();
        when(deviceCompletion.complete(any(), any(), any(), any(), any(), any()))
                .thenThrow(new TpmAttestException(TpmDenyCode.DEVICE_NOT_ELIGIBLE, "decommissioned"));

        assertThrows(TpmAttestException.class, () -> service.markConsumed(ENROLL_ID, completion(null)));

        verify(bindings, never()).save(any());
        verify(bindings, never()).revokeActive(any(), any(), any(), any());
    }

    @Test
    void missingEnrollmentDeniesBeforeCompletionAndWritesNoBinding() {
        when(enrollments.findById(ENROLL_ID)).thenReturn(Optional.empty());

        assertThrows(TpmAttestException.class, () -> service.markConsumed(ENROLL_ID, completion(null)));

        verify(deviceCompletion, never()).complete(any(), any(), any(), any(), any(), any());
        verify(bindings, never()).save(any());
    }
}
