package com.example.ethics.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.ethics.model.CaseSanction;
import com.example.ethics.model.CaseSanction.Band;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.CaseSanctionRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.RetaliationCheckRepository;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** ES-213 (#3375) — what the staff surface refuses, and why each refusal exists. */
class CaseChainServiceTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-0000-0000-0000-000000000009");
    private static final UUID CASE = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final StaffContext STAFF = new StaffContext(ORG, "kc-subject-1");

    private final EthicsCaseRepository cases = mock(EthicsCaseRepository.class);
    private final CaseSanctionRepository sanctions = mock(CaseSanctionRepository.class);
    private final RetaliationCheckRepository checks = mock(RetaliationCheckRepository.class);
    private final EthicsAuthorization authorization = mock(EthicsAuthorization.class);
    private final SecretHasher secrets = mock(SecretHasher.class);
    private CaseChainService service;

    @BeforeEach
    void setUp() {
        // sha256 is overloaded, so the matcher needs the type to pick one.
        when(secrets.sha256(any(String.class))).thenReturn("a".repeat(64));
        service = new CaseChainService(cases, sanctions, checks, authorization, secrets);
    }

    /**
     * Built into a local before it reaches when(...): Mockito cannot stub one mock while
     * evaluating the arguments of another stubbing call, and the failure it raises names
     * the calling test rather than the nesting.
     */
    private EthicsCase closedCase(UUID orgId) {
        EthicsCase item = mock(EthicsCase.class);
        when(item.getOrgId()).thenReturn(orgId);
        when(item.getStatus()).thenReturn("CLOSED");
        return item;
    }

    @Test
    void aSanctionOnAnOpenCaseIsRefused() {
        EthicsCase open = mock(EthicsCase.class);
        when(open.getOrgId()).thenReturn(ORG);
        when(open.getStatus()).thenReturn("INVESTIGATING");
        when(cases.findById(CASE)).thenReturn(Optional.of(open));

        // Sanctioning before the case that justifies it has concluded inverts the chain:
        // the finding would be written to fit a decision already taken.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.recordSanction(STAFF, CASE, 25, Band.AGIR, null, "SUSPENSION"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        verify(sanctions, never()).save(any());
    }

    @Test
    void recordingASanctionDemandsHandlerNotViewer() {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN))
                .when(authorization).require(STAFF, "case_handler", CASE);
        // Reading that a sanction was applied and applying one are different powers, and
        // the second is the one that ends someone's employment.
        assertThrows(ResponseStatusException.class,
                () -> service.recordSanction(STAFF, CASE, 25, Band.AGIR, null, "SUSPENSION"));
        verify(cases, never()).findById(any());
    }

    @Test
    void aCaseInAnotherOrgReadsAsNotFoundRatherThanForbidden() {
        EthicsCase foreign = closedCase(OTHER_ORG);
        when(cases.findById(CASE)).thenReturn(Optional.of(foreign));
        // 403 would confirm the id exists somewhere. For a whistleblowing case that is
        // itself a disclosure: it tells a handler in one company that another company has
        // a case under an id they can now probe.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.sanctionsFor(STAFF, CASE));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatusCode());
    }

    @Test
    void scaleViolationsSurfaceAsBadRequestWithTheirOwnMessage() {
        EthicsCase closed = closedCase(ORG);
        when(cases.findById(CASE)).thenReturn(Optional.of(closed));
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.recordSanction(STAFF, CASE, 28, Band.ORTA, null, "WRITTEN_WARNING"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatusCode());
        // The scale's own words travel: "band ORTA is below what score 28 supports (AGIR)"
        // tells the handler what to change. A generic 400 would not.
        assertEquals(true, String.valueOf(e.getReason()).contains("ORTA"));
    }

    @Test
    void aConcludedCheckCannotBeConcludedAgain() {
        RetaliationCheck check = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 3,
                Instant.parse("2026-11-02T00:00:00Z"));
        check.conclude("İlk gözlem", "CONFIRMED", "İK kararı geri alındı", Set.of("DEMOTION"),
                "h", Instant.parse("2026-11-03T00:00:00Z"));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        EthicsCase closed = closedCase(ORG);
        when(cases.findById(CASE)).thenReturn(Optional.of(closed));

        // The one edit this record must not accept: a CONFIRMED finding rewritten to NONE
        // once the quarter's numbers are being read.
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.concludeCheck(STAFF, check.getId(), "İkinci gözlem", "NONE", null, Set.of()));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
        assertEquals("CONFIRMED", check.getRisk());
    }

    @Test
    void concludingRecordsTheJudgementAndTheAction() {
        RetaliationCheck check = new RetaliationCheck(UUID.randomUUID(), CASE, ORG, (short) 6,
                Instant.parse("2027-02-02T00:00:00Z"));
        when(checks.findById(check.getId())).thenReturn(Optional.of(check));
        EthicsCase closed = closedCase(ORG);
        when(cases.findById(CASE)).thenReturn(Optional.of(closed));
        when(checks.save(any(RetaliationCheck.class))).thenAnswer(i -> i.getArgument(0));

        RetaliationCheck saved = service.concludeCheck(STAFF, check.getId(),
                "Görev değişikliği bildirildi", "SUSPECTED", "İK ile görüşüldü, karar askıya alındı",
                Set.of("DUTY_TRANSFER"));

        assertEquals("SUSPECTED", saved.getRisk());
        assertEquals("İK ile görüşüldü, karar askıya alındı", saved.getAction());
    }

    @Test
    void anOverturnedSanctionCannotBeApplied() {
        CaseSanction sanction = new CaseSanction(UUID.randomUUID(), CASE, ORG, 25, Band.AGIR, null,
                "SUSPENSION", "h", Instant.parse("2026-08-02T00:00:00Z"));
        sanction.moveAppeal("REQUESTED");
        sanction.moveAppeal("OVERTURNED");
        when(sanctions.findById(sanction.getId())).thenReturn(Optional.of(sanction));
        EthicsCase closed = closedCase(ORG);
        when(cases.findById(CASE)).thenReturn(Optional.of(closed));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service.applySanction(STAFF, sanction.getId(), "İK dosyasında imzalı tebliğ"));
        assertEquals(HttpStatus.CONFLICT, e.getStatusCode());
    }
}
