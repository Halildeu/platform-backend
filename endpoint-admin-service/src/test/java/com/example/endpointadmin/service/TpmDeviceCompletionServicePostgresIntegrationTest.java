package com.example.endpointadmin.service;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointEnrollment;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.model.EndpointTpmDeviceIdentity;
import com.example.endpointadmin.model.MachineCertChannel;
import com.example.endpointadmin.repository.EndpointDeviceRepository;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import com.example.endpointadmin.repository.EndpointTpmDeviceIdentityRepository;
import com.example.endpointadmin.security.TpmVaultCertExtractor;
import com.example.endpointadmin.tpmattest.TpmAttestException;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * Faz 22.6 #548 Phase 1.5 (Codex {@code 019eff93}) — PG-level proof of {@link TpmDeviceCompletionService}: the
 * canonical TPM device-completion. Drives {@code complete(...)} against a real PG 16 (Flyway V75/V76) with a mocked
 * audit sink (the chain is proven elsewhere). Asserts the merge-blocking invariants: tenant-scoped EK identity as
     * the sole adoption authority, VAULT_TPM cert register/rotate (channel-scoped revoke-before-insert), AD_CS and
     * VAULT_TPM coexistence, the channel CHECK, cross-tenant independence, decommission no-revive, and the issued-cert
     * SAN cross-check.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class TpmDeviceCompletionServicePostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    @Autowired private EndpointDeviceRepository devices;
    @Autowired private EndpointMachineCertRepository certs;
    @Autowired private EndpointTpmDeviceIdentityRepository identities;
    @Autowired private EntityManager em;

    private static final Instant NOW = Instant.ofEpochMilli(1_700_000_000_000L);
    private static final String EK_A = "a1".repeat(32); // 64 lowercase hex
    private static final String EK_B = "b2".repeat(32);

    private TpmDeviceCompletionService service;
    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        service = new TpmDeviceCompletionService(devices, certs, identities, mock(EndpointAuditService.class));
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
    }

    private static TpmVaultCertExtractor.ParsedVaultCert vaultCert(String ek, String serialThumb) {
        return new TpmVaultCertExtractor.ParsedVaultCert(
                ek, "serial-" + serialThumb, "thumb-" + serialThumb, "CN=Vault TPM CA", "CN=device",
                NOW.minusSeconds(60), NOW.plusSeconds(31_536_000L));
    }

    private static EndpointEnrollment transientEnrollment() {
        return new EndpointEnrollment(); // complete() only calls setDevice() on it
    }

    private List<EndpointMachineCert> activeCerts(UUID deviceId) {
        em.flush();
        em.clear();
        return certs.findAll().stream()
                .filter(c -> c.getDevice().getId().equals(deviceId) && c.getRevokedAt() == null)
                .toList();
    }

    @Test
    void deviceLessFirstEnrollment_createsDeviceIdentityAndActiveVaultTpmCert() {
        EndpointEnrollment enr = transientEnrollment();

        UUID deviceId = service.complete(tenantA, EK_A, vaultCert(EK_A, "c1"), enr, null, NOW);

        assertThat(deviceId).isNotNull();
        assertThat(enr.getDevice()).isNotNull();
        assertThat(enr.getDevice().getId()).isEqualTo(deviceId);

        EndpointDevice device = devices.findById(deviceId).orElseThrow();
        assertThat(device.getTenantId()).isEqualTo(tenantA);
        assertThat(device.getOrgId()).isEqualTo(tenantA);
        assertThat(device.getHostname()).isEqualTo("tpm-" + EK_A);
        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ONLINE);

        // canonical identity row written (the sole adoption authority)
        EndpointTpmDeviceIdentity identity =
                identities.findByTenantIdAndEkPubSha256(tenantA, EK_A).orElseThrow();
        assertThat(identity.getDeviceId()).isEqualTo(deviceId);

        // exactly one active VAULT_TPM cert, SAN tpm:{ek}, object_guid NULL (channel CHECK)
        List<EndpointMachineCert> active = activeCerts(deviceId);
        assertThat(active).hasSize(1);
        EndpointMachineCert cert = active.get(0);
        assertThat(cert.getChannel()).isEqualTo(MachineCertChannel.VAULT_TPM);
        assertThat(cert.getSanUri()).isEqualTo("tpm:" + EK_A);
        assertThat(cert.getObjectGuid()).isNull();
    }

    @Test
    void reEnrollmentSameEk_reusesDeviceAndRotatesCert() {
        EndpointEnrollment enr1 = transientEnrollment();
        UUID first = service.complete(tenantA, EK_A, vaultCert(EK_A, "c1"), enr1, null, NOW);
        em.flush();
        em.clear();

        EndpointEnrollment enr2 = transientEnrollment();
        UUID second = service.complete(tenantA, EK_A, vaultCert(EK_A, "c2"), enr2, null, NOW.plusSeconds(10));

        assertThat(second).as("re-enrollment of the same EK reuses the canonical device").isEqualTo(first);
        // exactly one active cert (the new thumbprint), the prior one revoked
        List<EndpointMachineCert> active = activeCerts(first);
        assertThat(active).hasSize(1);
        assertThat(active.get(0).getCertThumbprint()).isEqualTo("thumb-c2");
        assertThat(certs.findAll().stream().filter(c -> c.getRevokedAt() != null)).hasSize(1);
        // one identity row only (not duplicated)
        assertThat(identities.count()).isEqualTo(1L);
    }

    @Test
    void decommissionedDevice_reAttest_deniesNoRevive() {
        EndpointEnrollment enr1 = transientEnrollment();
        UUID deviceId = service.complete(tenantA, EK_A, vaultCert(EK_A, "c1"), enr1, null, NOW);
        em.flush();
        EndpointDevice device = devices.findById(deviceId).orElseThrow();
        device.setStatus(DeviceStatus.DECOMMISSIONED);
        devices.saveAndFlush(device);
        em.clear();

        EndpointEnrollment enr2 = transientEnrollment();
        assertThatThrownBy(() -> service.complete(tenantA, EK_A, vaultCert(EK_A, "c2"), enr2, null, NOW.plusSeconds(10)))
                .isInstanceOf(TpmAttestException.class);
    }

    @Test
    void crossTenantSameEk_createsTwoIndependentDevices() {
        UUID a = service.complete(tenantA, EK_A, vaultCert(EK_A, "ca"), transientEnrollment(), null, NOW);
        em.flush();
        em.clear();
        UUID b = service.complete(tenantB, EK_A, vaultCert(EK_A, "cb"), transientEnrollment(), null, NOW);

        assertThat(b).as("same physical EK in two tenants → two independent devices").isNotEqualTo(a);
        assertThat(identities.findByTenantIdAndEkPubSha256(tenantA, EK_A).orElseThrow().getDeviceId()).isEqualTo(a);
        assertThat(identities.findByTenantIdAndEkPubSha256(tenantB, EK_A).orElseThrow().getDeviceId()).isEqualTo(b);
        // both VAULT_TPM certs share the SAN tpm:{ek} but are tenant-scoped-active (not a global collision)
        assertThat(activeCerts(a)).hasSize(1);
        assertThat(activeCerts(b)).hasSize(1);
    }

    @Test
    void preBoundDevice_registersVaultTpmCertAndCreatesIdentity_preservingPriorAdcsCert() {
        // Seed an AD_CS device + its active AD_CS machine-cert (the pre-bound target upgrading to TPM-native).
        EndpointDevice adcs = new EndpointDevice();
        adcs.setTenantId(tenantA);
        adcs.setOrgId(tenantA);
        adcs.setHostname("ad-host-1");
        adcs.setMachineFingerprint("fp-ad-1");
        adcs.setStatus(DeviceStatus.ONLINE);
        adcs.setEnrolledAt(NOW);
        EndpointDevice target = devices.saveAndFlush(adcs);
        certs.saveAndFlush(adcsCert(target));
        em.flush();
        em.clear();

        EndpointEnrollment enr = transientEnrollment();
        UUID resolved = service.complete(tenantA, EK_A, vaultCert(EK_A, "c1"), enr, target.getId(), NOW.plusSeconds(5));

        assertThat(resolved).isEqualTo(target.getId());
        // identity now maps the EK to the pre-bound device
        assertThat(identities.findByTenantIdAndEkPubSha256(tenantA, EK_A).orElseThrow().getDeviceId())
                .isEqualTo(target.getId());
        // AD_CS product-channel cert remains active, while the VAULT_TPM attestation cert gets its own active slot.
        List<EndpointMachineCert> active = activeCerts(target.getId());
        assertThat(active).hasSize(2);
        assertThat(active)
                .extracting(EndpointMachineCert::getChannel)
                .containsExactlyInAnyOrder(MachineCertChannel.AD_CS, MachineCertChannel.VAULT_TPM);
        assertThat(active.stream()
                .filter(c -> c.getChannel() == MachineCertChannel.VAULT_TPM)
                .findFirst()
                .orElseThrow()
                .getSanUri()).isEqualTo("tpm:" + EK_A);
    }

    @Test
    void preBoundEkAlreadyBoundToDifferentDevice_denies() {
        // EK_A is first bound (device-less) to deviceA.
        UUID deviceA = service.complete(tenantA, EK_A, vaultCert(EK_A, "ca"), transientEnrollment(), null, NOW);
        em.flush();
        em.clear();
        // A different pre-bound target device in the same tenant.
        EndpointDevice other = new EndpointDevice();
        other.setTenantId(tenantA);
        other.setOrgId(tenantA);
        other.setHostname("other-host");
        other.setStatus(DeviceStatus.ONLINE);
        other.setEnrolledAt(NOW);
        UUID otherId = devices.saveAndFlush(other).getId();
        em.flush();
        em.clear();

        EndpointEnrollment enr = transientEnrollment();
        assertThatThrownBy(() -> service.complete(tenantA, EK_A, vaultCert(EK_A, "c2"), enr, otherId, NOW.plusSeconds(10)))
                .as("anti-hijack: EK already maps to deviceA, refuse to bind it to a different device")
                .isInstanceOf(TpmAttestException.class);
        assertThat(deviceA).isNotEqualTo(otherId);
    }

    @Test
    void issuedCertSanMismatch_deniesFailClosed() {
        EndpointEnrollment enr = transientEnrollment();
        // The Vault cert's SAN identity (EK_B) does not equal the L1-bound EK (EK_A) → fail-closed.
        assertThatThrownBy(() -> service.complete(tenantA, EK_A, vaultCert(EK_B, "c1"), enr, null, NOW))
                .isInstanceOf(TpmAttestException.class);
        assertThat(devices.count()).isZero();
        assertThat(identities.count()).isZero();
    }

    @Test
    void channelCheck_vaultTpmRowWithObjectGuid_rejected() {
        EndpointDevice d = devices.saveAndFlush(freshDevice());
        EndpointMachineCert bad = vaultTpmCert(d);
        bad.setObjectGuid(UUID.randomUUID()); // VAULT_TPM must have NULL object_guid (CHECK)
        assertThatThrownBy(() -> certs.saveAndFlush(bad)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void channelCheck_adcsRowWithNullObjectGuid_rejected() {
        EndpointDevice d = devices.saveAndFlush(freshDevice());
        EndpointMachineCert bad = vaultTpmCert(d);
        bad.setChannel(MachineCertChannel.AD_CS);
        bad.setObjectGuid(null); // AD_CS must have a NON-NULL object_guid (CHECK)
        assertThatThrownBy(() -> certs.saveAndFlush(bad)).isInstanceOf(DataIntegrityViolationException.class);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private EndpointDevice freshDevice() {
        EndpointDevice d = new EndpointDevice();
        d.setTenantId(tenantA);
        d.setOrgId(tenantA);
        d.setHostname("h-" + UUID.randomUUID());
        d.setStatus(DeviceStatus.ONLINE);
        d.setEnrolledAt(NOW);
        return d;
    }

    private EndpointMachineCert adcsCert(EndpointDevice device) {
        EndpointMachineCert c = new EndpointMachineCert();
        c.setDevice(device);
        c.setTenantId(tenantA);
        c.setChannel(MachineCertChannel.AD_CS);
        UUID guid = UUID.randomUUID();
        c.setSanUri("adcomputer:" + guid);
        c.setObjectGuid(guid);
        c.setCertSerial("ad-serial");
        c.setCertThumbprint("ad-thumb");
        c.setCertIssuer("CN=AD CS");
        c.setCertSubject("CN=ad-host-1");
        c.setCertNotBefore(NOW.minusSeconds(60));
        c.setCertNotAfter(NOW.plusSeconds(31_536_000L));
        c.setMachineFingerprint("fp-ad-1");
        c.setEnrolledAt(NOW);
        return c;
    }

    private EndpointMachineCert vaultTpmCert(EndpointDevice device) {
        EndpointMachineCert c = new EndpointMachineCert();
        c.setDevice(device);
        c.setTenantId(tenantA);
        c.setChannel(MachineCertChannel.VAULT_TPM);
        c.setSanUri("tpm:" + EK_A);
        c.setObjectGuid(null);
        c.setCertSerial("vt-serial");
        c.setCertThumbprint("vt-thumb");
        c.setCertIssuer("CN=Vault TPM CA");
        c.setCertSubject("CN=device");
        c.setCertNotBefore(NOW.minusSeconds(60));
        c.setCertNotAfter(NOW.plusSeconds(31_536_000L));
        c.setMachineFingerprint("tpm:" + EK_A);
        c.setEnrolledAt(NOW);
        return c;
    }
}
