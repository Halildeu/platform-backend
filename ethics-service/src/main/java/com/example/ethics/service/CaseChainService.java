package com.example.ethics.service;

import com.example.ethics.model.CaseSanction;
import com.example.ethics.model.EthicsCase;
import com.example.ethics.model.RetaliationCheck;
import com.example.ethics.repository.CaseSanctionRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.repository.RetaliationCheckRepository;
import com.example.ethics.security.EthicsAuthorization;
import com.example.ethics.security.StaffContext;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * ES-213 (#3375) — the staff surface for the last two links of the case chain.
 *
 * <p>Every method requires {@code case_handler} rather than {@code case_viewer}. Reading
 * that a sanction was applied and applying one are different powers, and the second is the
 * one that ends someone's employment.
 */
@Service
public class CaseChainService {

    private final EthicsCaseRepository cases;
    private final CaseSanctionRepository sanctions;
    private final RetaliationCheckRepository checks;
    private final EthicsAuthorization authorization;
    private final SecretHasher secrets;

    public CaseChainService(EthicsCaseRepository cases, CaseSanctionRepository sanctions,
                            RetaliationCheckRepository checks, EthicsAuthorization authorization,
                            SecretHasher secrets) {
        this.cases = cases;
        this.sanctions = sanctions;
        this.checks = checks;
        this.authorization = authorization;
        this.secrets = secrets;
    }

    // ---- sanctions --------------------------------------------------------

    @Transactional
    public CaseSanction recordSanction(StaffContext staff, UUID caseId, int severityScore,
                                       CaseSanction.Band band, String escalationReason,
                                       String sanctionType) {
        EthicsCase item = requireCase(staff, caseId);
        // A sanction on an open case would be a decision taken before the case that is
        // supposed to justify it concluded. The chain is assess, decide, then sanction —
        // not sanction and then find a reason.
        if (!"CLOSED".equals(item.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CASE_NOT_CLOSED");
        }
        try {
            return sanctions.save(new CaseSanction(UUID.randomUUID(), caseId, item.getOrgId(),
                    severityScore, band, escalationReason, sanctionType,
                    secrets.sha256(staff.subject()), Instant.now()));
        } catch (IllegalArgumentException e) {
            // The severity-scale rules speak in the caller's terms, so the message travels
            // rather than being flattened into a generic 400.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @Transactional
    public CaseSanction applySanction(StaffContext staff, UUID sanctionId, String verificationNote) {
        CaseSanction sanction = requireSanction(staff, sanctionId);
        try {
            sanction.markApplied(secrets.sha256(staff.subject()), verificationNote, Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return sanctions.save(sanction);
    }

    @Transactional
    public CaseSanction moveAppeal(StaffContext staff, UUID sanctionId, String next) {
        CaseSanction sanction = requireSanction(staff, sanctionId);
        try {
            sanction.moveAppeal(next);
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
        return sanctions.save(sanction);
    }

    @Transactional(readOnly = true)
    public List<CaseSanction> sanctionsFor(StaffContext staff, UUID caseId) {
        requireCase(staff, caseId);
        return sanctions.findAllByCaseIdOrderByDecidedAtDesc(caseId);
    }

    // ---- retaliation checks -----------------------------------------------

    @Transactional(readOnly = true)
    public List<RetaliationCheck> checksFor(StaffContext staff, UUID caseId) {
        requireCase(staff, caseId);
        return checks.findAllByCaseIdOrderByPeriodMonthsAsc(caseId);
    }

    @Transactional
    public RetaliationCheck markAsked(StaffContext staff, UUID checkId) {
        RetaliationCheck check = requireCheck(staff, checkId);
        check.markAsked(Instant.now());
        return checks.save(check);
    }

    @Transactional
    public RetaliationCheck concludeCheck(StaffContext staff, UUID checkId, String observation,
                                          String risk, String action, Set<String> indicators) {
        RetaliationCheck check = requireCheck(staff, checkId);
        if (check.getClosedAt() != null) {
            // Re-concluding would let a CONFIRMED finding be rewritten to NONE later, which
            // is the one edit this record must not accept.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CHECK_ALREADY_CONCLUDED");
        }
        try {
            check.conclude(observation, risk, action, indicators, secrets.sha256(staff.subject()), Instant.now());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        return checks.save(check);
    }

    // ---- guards -----------------------------------------------------------

    private EthicsCase requireCase(StaffContext staff, UUID caseId) {
        authorization.require(staff, "case_handler", caseId);
        EthicsCase item = cases.findById(caseId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND"));
        // Tenant check after the authorization check and before anything is returned: a
        // handler in one organisation must not learn that a case id exists in another.
        if (!item.getOrgId().equals(staff.orgId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "CASE_NOT_FOUND");
        }
        return item;
    }

    private CaseSanction requireSanction(StaffContext staff, UUID sanctionId) {
        CaseSanction sanction = sanctions.findById(sanctionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SANCTION_NOT_FOUND"));
        requireCase(staff, sanction.getCaseId());
        return sanction;
    }

    private RetaliationCheck requireCheck(StaffContext staff, UUID checkId) {
        RetaliationCheck check = checks.findById(checkId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "CHECK_NOT_FOUND"));
        requireCase(staff, check.getCaseId());
        return check;
    }
}
