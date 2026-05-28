package com.example.endpointadmin.service;

import com.example.endpointadmin.config.TimeConfig;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentRequest;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.example.endpointadmin.security.TestX509Certs;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.security.cert.X509Certificate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3 — H2-based @DataJpaTest slice for the auto-enroll service.
 * Covers idempotency + fingerprint dedupe + cross-tenant boundary without
 * needing a real Postgres (partial unique index behaviors are exercised in
 * the Postgres integration test).
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
@Import({
        TimeConfig.class,
        MachineCertAutoEnrollService.class,
        EndpointAuditService.class,
        com.example.endpointadmin.audit.NoOpAuditChainLock.class
})
class MachineCertAutoEnrollServiceTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private MachineCertAutoEnrollService service;

    @Autowired
    private EndpointMachineCertRepository certRepository;

    @Autowired
    private EndpointDeviceRepository deviceRepository;

    private static AutoEnrollmentRequest sampleRequest(String fingerprint) {
        return new AutoEnrollmentRequest(
                fingerprint,
                "DESKTOP-TEST",
                "windows",
                "10.0.26200",
                "26200",
                "acik.local",
                "amd64",
                "0.1.0",
                1
        );
    }

    @Test
    void firstEnrollmentReturns201AndCreatesActiveCertRow() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        var outcome = service.autoEnroll(cert, TENANT_A, sampleRequest("fp-001"));

        assertThat(outcome.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(outcome.body().status()).isEqualTo("enrolled");
        assertThat(outcome.body().deviceId()).isNotNull();
        assertThat(outcome.body().certInfo().sanUri())
                .isEqualTo("adcomputer:" + guid.toString().toLowerCase());

        var active = certRepository.findActiveBySanUri(outcome.body().certInfo().sanUri());
        assertThat(active).isPresent();
        assertThat(active.get().getTenantId()).isEqualTo(TENANT_A);
        assertThat(active.get().getMachineFingerprint()).isEqualTo("fp-001");
    }

    @Test
    void secondEnrollmentWithSameCertReturns200AlreadyEnrolled() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        var first = service.autoEnroll(cert, TENANT_A, sampleRequest("fp-idem"));
        var second = service.autoEnroll(cert, TENANT_A, sampleRequest("fp-idem"));

        assertThat(first.status()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.status()).isEqualTo(HttpStatus.OK);
        assertThat(second.body().status()).isEqualTo("already-enrolled");
        assertThat(second.body().deviceId()).isEqualTo(first.body().deviceId());
    }

    @Test
    void crossTenantPresentationReturns403() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        service.autoEnroll(cert, TENANT_A, sampleRequest("fp-x"));

        assertThatThrownBy(() -> service.autoEnroll(cert, TENANT_B, sampleRequest("fp-x")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("TENANT_BOUNDARY");
    }

    @Test
    void distinctActiveCertWithSameFingerprintReturns409() {
        UUID guid1 = UUID.randomUUID();
        UUID guid2 = UUID.randomUUID();
        X509Certificate cert1 = TestX509Certs.validClientCert(guid1);
        X509Certificate cert2 = TestX509Certs.validClientCert(guid2);

        service.autoEnroll(cert1, TENANT_A, sampleRequest("fp-clone"));

        assertThatThrownBy(() ->
                service.autoEnroll(cert2, TENANT_A, sampleRequest("fp-clone")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("FINGERPRINT_CONFLICT");
    }

    @Test
    void expiredCertReturns401() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.builder()
                .objectGuid(guid)
                .expiredDaysAgo(1)
                .build();

        assertThatThrownBy(() ->
                service.autoEnroll(cert, TENANT_A, sampleRequest("fp-exp")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CERT_EXPIRED");
    }

    @Test
    void missingClientAuthEkuReturns401() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.builder()
                .objectGuid(guid)
                .clientAuth(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() ->
                service.autoEnroll(cert, TENANT_A, sampleRequest("fp-noeku")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CERT_EKU_MISSING_CLIENT_AUTH");
    }

    @Test
    void missingSanUriReturns401() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.builder()
                .objectGuid(guid)
                .includeSanUri(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() ->
                service.autoEnroll(cert, TENANT_A, sampleRequest("fp-nosan")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("CERT_SAN_URI_MISSING");
    }

    @Test
    void deviceIsCreatedOnFirstEnroll() {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);

        var outcome = service.autoEnroll(cert, TENANT_A, sampleRequest("fp-dev"));

        var device = deviceRepository.findById(outcome.body().deviceId());
        assertThat(device).isPresent();
        assertThat(device.get().getTenantId()).isEqualTo(TENANT_A);
        assertThat(device.get().getHostname()).isEqualTo("DESKTOP-TEST");
        assertThat(device.get().getMachineFingerprint()).isEqualTo("fp-dev");
        assertThat(device.get().getDomainName()).isEqualTo("acik.local");
    }
}
