package com.example.ethics.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.ethics.api.EthicsDtos.ReportMode;
import com.example.ethics.api.EthicsDtos.ReporterIdentityPayload;
import com.example.ethics.config.ReporterIdentityProperties;
import com.example.ethics.model.ReporterIdentity;
import com.example.ethics.model.RevealRequest;
import com.example.ethics.repository.ReporterIdentityRepository;
import com.example.ethics.repository.RevealRequestRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * ES-212 — who may see the reporter, per mode.
 *
 * <p>These are the invariants the two modes promise. CONFIDENTIAL says the handlers do not
 * learn who reported unless a reveal has actually been executed; NAMED says they do. A
 * regression in either direction breaks a promise made on the intake form, so both are
 * pinned here rather than left to the controller layer.
 */
class ReporterIdentityVisibilityTest {

    private static final UUID CASE = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final ReporterIdentityPayload AYSE =
            new ReporterIdentityPayload("Ayşe Yılmaz", "ayse@example.com", null, "Finans", null);

    private final ReporterIdentityRepository identities = mock(ReporterIdentityRepository.class);
    private final RevealRequestRepository reveals = mock(RevealRequestRepository.class);
    private final ReporterIdentityCrypto crypto = operationalCrypto();
    private final ReporterIdentityService service =
            new ReporterIdentityService(identities, reveals, crypto, new ObjectMapper());

    private static ReporterIdentityCrypto operationalCrypto() {
        ReporterIdentityProperties properties = new ReporterIdentityProperties();
        properties.setActiveKeyId("v1");
        properties.setKeys(Map.of("v1", Base64.getEncoder().encodeToString(new byte[32])));
        return new ReporterIdentityCrypto(properties);
    }

    /** Seals a payload the way {@code store} would, so the read path sees a realistic row. */
    private ReporterIdentity sealed(ReportMode mode) {
        String json;
        try {
            json = new ObjectMapper().writeValueAsString(AYSE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        ReporterIdentityCrypto.Sealed s = crypto.seal(CASE, json);
        return new ReporterIdentity(CASE, mode.name(), s.keyId(), s.nonce(), s.ciphertext(), Instant.EPOCH);
    }

    @Test
    void anAnonymousCaseHasNoIdentityAtAll() {
        when(identities.findById(CASE)).thenReturn(Optional.empty());
        assertEquals(ReporterIdentityService.State.NONE, service.readForHandler(CASE).state());
    }

    @Test
    void confidentialStaysSealedWithoutAnExecutedReveal() {
        when(identities.findById(CASE)).thenReturn(Optional.of(sealed(ReportMode.CONFIDENTIAL)));
        when(reveals.findAllByCaseIdOrderByRequestedAtDesc(CASE)).thenReturn(List.of());

        ReporterIdentityService.IdentityAccess access = service.readForHandler(CASE);
        assertEquals(ReporterIdentityService.State.SEALED, access.state());
        assertNull(access.payload(), "a sealed identity must carry no payload whatsoever");
    }

    @Test
    void confidentialStaysSealedWhileTheRevealIsMerelyApproved() {
        // READY means two approvers signed but the reveal was never executed. Treating that
        // as good enough would quietly drop the last control in the chain — the deliberate,
        // audited act of actually opening it.
        RevealRequest ready = new RevealRequest(UUID.randomUUID(), CASE, "req", "Requester",
                "KVKK_MD28", "Cumhuriyet Başsavcılığı", "2026/123", "yazılı talep", Instant.EPOCH);
        ready.recordFirstApproval("approver-1", "First Approver", "Hukuk Müdürü", Instant.EPOCH);
        ready.recordSecondApproval("approver-2", "Second Approver", "Etik Kurul", Instant.EPOCH);

        when(identities.findById(CASE)).thenReturn(Optional.of(sealed(ReportMode.CONFIDENTIAL)));
        when(reveals.findAllByCaseIdOrderByRequestedAtDesc(CASE)).thenReturn(List.of(ready));

        assertEquals(ReporterIdentityService.State.SEALED, service.readForHandler(CASE).state());
    }

    @Test
    void confidentialOpensOnceTheRevealIsExecuted() {
        RevealRequest executed = new RevealRequest(UUID.randomUUID(), CASE, "req", "Requester",
                "KVKK_MD28", "Cumhuriyet Başsavcılığı", "2026/123", "yazılı talep", Instant.EPOCH);
        executed.markExecuted("executor", Instant.EPOCH);

        when(identities.findById(CASE)).thenReturn(Optional.of(sealed(ReportMode.CONFIDENTIAL)));
        when(reveals.findAllByCaseIdOrderByRequestedAtDesc(CASE)).thenReturn(List.of(executed));

        ReporterIdentityService.IdentityAccess access = service.readForHandler(CASE);
        assertEquals(ReporterIdentityService.State.VISIBLE, access.state());
        assertNotNull(access.payload());
        assertEquals("Ayşe Yılmaz", access.payload().fullName());
    }

    @Test
    void namedIsVisibleWithoutAnyReveal() {
        when(identities.findById(CASE)).thenReturn(Optional.of(sealed(ReportMode.NAMED)));
        // Not stubbed on purpose: a named report must not consult the reveal ledger at all.
        ReporterIdentityService.IdentityAccess access = service.readForHandler(CASE);
        assertEquals(ReporterIdentityService.State.VISIBLE, access.state());
        assertEquals("Ayşe Yılmaz", access.payload().fullName());
    }

    @Test
    void storingAnAnonymousIdentityIsRefusedOutright() {
        assertThrows(IllegalArgumentException.class,
                () -> service.store(CASE, ReportMode.ANONYMOUS, AYSE, Instant.EPOCH));
    }

    @Test
    void whatIsHandedToTheDatabaseCarriesNoReadableIdentity() {
        List<ReporterIdentity> saved = new ArrayList<>();
        when(identities.save(any(ReporterIdentity.class))).thenAnswer(inv -> {
            saved.add(inv.getArgument(0));
            return inv.getArgument(0);
        });

        service.store(CASE, ReportMode.CONFIDENTIAL, AYSE, Instant.EPOCH);

        assertEquals(1, saved.size());
        String bytes = new String(saved.get(0).getCiphertext(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertEquals(-1, bytes.indexOf("Ayşe"), "the name reached the row in readable form");
        assertEquals(-1, bytes.indexOf("ayse@example.com"), "the e-mail reached the row in readable form");
    }
}
